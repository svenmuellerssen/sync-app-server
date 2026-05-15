package de.sync.app.server

import de.sync.app.server.graph.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppointmentService(
    private val appointmentRepository: AppointmentRepository,
    private val calendarRepository: CalendarRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val googleCalendarRepository: GoogleCalendarRepository,
    private val contactRepository: ContactRepository,
    private val slotService: SlotService,
) {
    private val log = LoggerFactory.getLogger(AppointmentService::class.java)

    data class BatchResult(
        val stored: Int,
        val skipped: Int,
        /** CalendarNodes created during this batch (first-ever sync for these calendars). App writes serverCalendarId → _SYNC_ID. */
        val newCalendars: List<CalendarNode>,
    )

    private enum class SaveStatus { STORED, SKIPPED }

    /**
     * Processes a full upload batch for one account.
     * All saves are in a single transaction; slot cache is invalidated once at the end.
     */
    @Transactional
    fun processBatch(dtos: List<AppointmentDtoRequest>, accountName: String): BatchResult {
        val now = System.currentTimeMillis()
        val newCalendars = mutableMapOf<String, CalendarNode>() // calendarId → node (dedup within batch)
        var stored = 0
        var skipped = 0

        for (dto in dtos) {
            try {
                val status = processSingle(dto, accountName, now, newCalendars)
                if (status == SaveStatus.STORED) stored++ else skipped++
            } catch (e: Exception) {
                log.warn("Skipping appointment syncId={} due to error: {}", dto.syncId, e.message)
                skipped++
            }
        }

        slotService.invalidateAccount(accountName)

        // Invalidate slot caches for all members/owners of any touched shared calendars,
        // so that their slot proposals reflect the newly uploaded appointments immediately.
        val touchedSharedCalIds = dtos.mapNotNull { it.sharedCalendarId }.toSet()
        if (touchedSharedCalIds.isNotEmpty()) {
            val affectedAccounts = sharedCalendarRepository
                .findMemberUsernamesByCalendarIds(touchedSharedCalIds)
                .toSet()
                .minus(accountName)
            for (username in affectedAccounts) {
                slotService.invalidateAccount(username)
            }
        }

        return BatchResult(stored = stored, skipped = skipped, newCalendars = newCalendars.values.toList())
    }

    /**
     * Explicitly deletes an appointment by setting deletedAt (tombstone).
     * Returns true if found and archived, false if not found (→ 404).
     * The HAS_APPOINTMENT edge is kept so the tombstone is detectable by the sync manifest.
     */
    @Transactional
    fun deleteByExplicit(syncId: String, accountName: String): Boolean {
        val node = appointmentRepository.findCurrentBySyncId(accountName, syncId) ?: return false
        appointmentRepository.softArchiveById(node.id!!, System.currentTimeMillis())
        slotService.invalidateAccount(accountName)
        return true
    }

    private fun processSingle(
        dto: AppointmentDtoRequest,
        accountName: String,
        now: Long,
        newCalendarsOut: MutableMap<String, CalendarNode>,
    ): SaveStatus {
        val removedDuplicates = appointmentRepository.deduplicateHasAppointmentEdges(accountName, dto.syncId, now)
        if (removedDuplicates > 0) {
            log.warn(
                "Deduplicated {} stale HAS_APPOINTMENT edge(s) for account={} syncId={}",
                removedDuplicates, accountName, dto.syncId
            )
        }

        val existing = appointmentRepository.findCurrentOrArchivedBySyncId(accountName, dto.syncId)

        // Stale-overwrite protection: never regress to an older version
        if (existing != null && dto.lastUpdatedAt < existing.lastUpdatedAt) {
            return SaveStatus.SKIPPED
        }

        // Content-hash dedup: skip if nothing changed
        val newHash = hashAppointment(dto)
        if (existing != null && existing.contentHash.isNotEmpty() && existing.contentHash == newHash) {
            if (existing.deletedAt != null) {
                // Archived appointment re-synced with unchanged content → un-archive without new version
                appointmentRepository.clearDeletedAt(existing.id!!)
                return SaveStatus.STORED
            }
            return SaveStatus.SKIPPED
        }

        // Resolve the CalendarNode for personal appointments
        val calendarNode: CalendarNode? = if (dto.sharedCalendarId == null) {
            resolveOrCreateCalendarNode(dto, accountName, newCalendarsOut)
        } else null

        // Resolve SharedCalendar (owner or member check)
        // Must use derived query (findByCalendarIdAndDeletedAtIsNull) — it loads owner + members via SDN6 automatic
        // relationship loading. The custom @Query findByCalendarId() returns only node properties, not relationships.
        val sharedCal: SharedCalendarNode? = dto.sharedCalendarId?.let { scId ->
            val cal = sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull(scId)
                ?: throw IllegalArgumentException("SharedCalendar not found: $scId")
            val isOwner = cal.owner?.username == accountName
            val isMember = cal.members.any { it.username == accountName }
            if (!isOwner && !isMember) throw IllegalStateException("Access denied to shared calendar $scId")
            cal
        }

        // Resolve/upsert GoogleCalendarNode (only for Google-type calendars)
        val googleCal: GoogleCalendarNode? = resolveGoogleCalendar(dto, accountName)

        // Build attendees
        val attendees = dto.attendees.map { a ->
            val contact = a.email?.let { contactRepository.findByAccountNameAndEmail(accountName, it) }
                ?: a.contactLookupKey?.let { contactRepository.findByLookupKeyAndAccountName(it, accountName) }
            AttendeeNode(name = a.name, email = a.email, type = a.type, status = a.status, contact = contact)
        }.toMutableList()

        // Build reminders
        val reminders = dto.reminders.map { r -> ReminderNode(minutes = r.minutes, method = r.method) }.toMutableList()

        // Soft-archive current version (removes HAS_APPOINTMENT edge, keeps node for history)
        if (existing != null) {
            appointmentRepository.removeHasAppointmentEdge(existing.id!!)
        }

        // Resolve the effective calendarId for denormalization
        val effectiveCalendarId = calendarNode?.calendarId ?: dto.sharedCalendarId

        val node = AppointmentNode(
            syncId = dto.syncId,
            versionId = UUID.randomUUID().toString(),
            contentHash = newHash,
            calendarId = effectiveCalendarId,
            versionCreatedAt = now,
            accountName = accountName,
            title = dto.title,
            description = dto.description,
            dtStart = dto.dtStart,
            dtEnd = dto.dtEnd,
            duration = dto.duration,
            allDay = dto.allDay,
            timezone = dto.timezone,
            rrule = dto.rrule,
            location = dto.location,
            organizer = dto.organizer,
            calendarName = dto.calendarName,
            calendarAccountType = dto.calendarAccountType,
            calendarAccountName = dto.calendarAccountName,
            calendarColor = dto.calendarColor,
            status = dto.status?.toString(),
            lastUpdatedAt = dto.lastUpdatedAt,
            createdAt = existing?.createdAt ?: now,
            attendees = attendees,
            reminders = reminders,
            sharedCalendar = sharedCal,
            googleCalendar = googleCal,
        )

        val saved = appointmentRepository.save(node)

        // Add HAS_APPOINTMENT edge from the owning calendar to the new node
        when {
            calendarNode != null ->
                calendarRepository.addHasAppointmentEdge(calendarNode.calendarId, saved.id!!)
            sharedCal != null ->
                appointmentRepository.addHasAppointmentEdgeFromSharedCalendar(sharedCal.calendarId, saved.id!!)
            // Google-only appointment with no calendarNode (should not happen with GOOGLE CalendarNode path)
        }

        // Link new version → previous version in history chain
        if (existing != null) {
            appointmentRepository.linkVersions(saved.id!!, existing.id!!)
        }

        return SaveStatus.STORED
    }

    /**
     * Finds or creates a CalendarNode for a personal (non-shared) appointment.
     *
     * Lookup order:
     * 1. If dto.serverCalendarId is set (subsequent syncs): look up by server UUID.
     *    Returns null (→ 400) if not found — reject rather than silently fall back.
     * 2. Bootstrap (first sync, serverCalendarId == null): find-or-create by natural key
     *    (name, accountName, calendarType).
     */
    private fun resolveOrCreateCalendarNode(
        dto: AppointmentDtoRequest,
        accountName: String,
        newCalendarsOut: MutableMap<String, CalendarNode>,
    ): CalendarNode? {
        val calType = if (dto.calendarAccountType == "com.google") "GOOGLE" else "LOCAL"

        // Subsequent syncs: use stable server UUID
        if (dto.serverCalendarId != null) {
            return calendarRepository.findByCalendarId(dto.serverCalendarId)
                ?: throw IllegalArgumentException("CalendarNode not found for serverCalendarId=${dto.serverCalendarId}")
        }

        // Bootstrap: find-or-create by natural key via atomic Neo4j MERGE (prevents race-condition duplicates)
        val name = dto.calendarName
            ?: return null // no name → can't identify calendar, skip edge creation

        // Check batch-local cache first (prevents duplicate creates within same batch)
        val batchCached = newCalendarsOut.values.firstOrNull {
            it.name == name && it.accountName == accountName && it.calendarType == calType
        }
        if (batchCached != null) return batchCached

        val newUuid = UUID.randomUUID().toString()
        val cal = calendarRepository.mergeByNaturalKey(
            name = name,
            accountName = accountName,
            calendarType = calType,
            calendarId = newUuid,
            color = dto.calendarColor,
        )
        if (cal.calendarId == newUuid) {
            // Newly created — record for response so the app writes serverCalendarId to _SYNC_ID
            newCalendarsOut[cal.calendarId] = cal
        }
        return cal
    }

    private fun resolveGoogleCalendar(dto: AppointmentDtoRequest, accountName: String): GoogleCalendarNode? {
        if (dto.calendarAccountType != "com.google" || dto.calendarId == null) return null

        val cal = googleCalendarRepository.findByCalendarIdAndAccountName(dto.calendarId, accountName)
            ?: GoogleCalendarNode(
                calendarId = dto.calendarId,
                displayName = dto.calendarName ?: dto.calendarId,
                calendarAccountName = dto.calendarAccountName ?: "",
                color = dto.calendarColor,
                accessLevel = dto.accessLevel,
                accountName = accountName,
            )

        // Sync attendee contacts onto the GoogleCalendarNode
        val attendeeEmails = dto.attendees.mapNotNull { it.email }.toSet()
        for (email in attendeeEmails) {
            val contact = contactRepository.findByAccountNameAndEmail(accountName, email)
            if (contact != null && cal.members.none { it.syncId == contact.syncId }) {
                cal.members.add(contact)
            }
        }

        return googleCalendarRepository.save(cal)
    }
}
