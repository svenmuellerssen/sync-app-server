package de.sync.app.server

import de.sync.app.server.dto.AppointmentManifest
import de.sync.app.server.dto.ContactManifest
import de.sync.app.server.dto.ManifestRequest
import de.sync.app.server.dto.ManifestResponse
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private data class DedupKey(val title: String, val dtStart: Long, val dtEnd: Long?, val rrule: String?)

@RestController
@RequestMapping("/sync")
class SyncController(
    private val contactRepository: ContactRepository,
    private val appointmentRepository: AppointmentRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val slotService: SlotService,
) {

    @Transactional
    @PostMapping("/manifest")
    fun manifest(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody request: ManifestRequest,
        req: HttpServletRequest,
    ): ResponseEntity<ManifestResponse> {
        val accountName = req.getAttribute("accountName") as String
        val emptyContacts = ContactManifest(emptyList(), emptyList(), emptyList())
        val emptyAppointments = AppointmentManifest(emptyList(), emptyList(), emptyList())

        val contactManifest = if (request.type == "contacts" || request.type == "all")
            buildContactManifest(request, accountName) else emptyContacts
        val appointmentManifest = if (request.type == "appointments" || request.type == "all")
            buildAppointmentManifest(request, accountName) else emptyAppointments

        return ResponseEntity.ok(ManifestResponse(contactManifest, appointmentManifest))
    }

    private fun buildContactManifest(request: ManifestRequest, accountName: String): ContactManifest {
        val localMap = request.contacts.associateBy { it.syncId }
        val serverNodes = contactRepository.findAllByAccountNameAndDeletedAtIsNull(accountName)
        val serverMap = serverNodes.associateBy { it.syncId }

        // Tombstone check: syncIds reported by phone that were explicitly deleted on the server.
        // The query correctly excludes false positives where a contact was merely updated
        // (old version archived with deletedAt, new version active with deletedAt=null).
        val localSyncIds = localMap.keys.toList()
        val tombstoneSyncIds = if (localSyncIds.isEmpty()) emptySet()
            else contactRepository.findAllTombstonedByAccountNameAndSyncIdIn(accountName, localSyncIds)
                .map { it.syncId }.toSet()

        val missingOnPhone = serverMap.keys - localMap.keys
        val toDownload = missingOnPhone.mapNotNull { serverMap[it]?.toDto() }
        // Exclude tombstoned syncIds from toUpload — don't re-upload explicitly deleted contacts.
        val toUpload = ((localMap.keys - serverMap.keys - tombstoneSyncIds) + serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && local.lastUpdatedAt > node.lastUpdatedAt
        }.map { it.syncId }).toList()
        val toUpdate = serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        return ContactManifest(
            toUpload = toUpload,
            toDownload = toDownload,
            toUpdate = toUpdate,
            toDeleteLocally = tombstoneSyncIds.toList(),
        )
    }

    private fun buildAppointmentManifest(request: ManifestRequest, accountName: String): AppointmentManifest {
        val runtimeDedupRemoved = appointmentRepository.deduplicateHasAppointmentEdgesForAccount(
            accountName = accountName,
            now = System.currentTimeMillis(),
        )
        var invalidatedSlotCache = false
        if (runtimeDedupRemoved > 0) {
            slotService.invalidateAccount(accountName)
            invalidatedSlotCache = true
        }

        val localMap = request.appointments.associateBy { it.syncId }
        val now = System.currentTimeMillis()

        // Include own shared-calendar appointments so the manifest sees them as "already on server"
        // and doesn't put them in toUpload every sync (Bug 2 fix).
        val personalNodes = appointmentRepository.findAllCurrentByAccountName(accountName)
        val ownSharedNodes = appointmentRepository.findAllCurrentSharedByAccountName(accountName)
        val serverNodes = personalNodes + ownSharedNodes
        val serverMap = serverNodes.associateBy { it.syncId }

        // G3 fix: dedup by content-key, soft-archive losers (preserve history) instead of hard-delete.
        // Keeps the oldest node (smallest createdAt) as the canonical version.
        var dedupArchived = false
        val surviving = serverNodes.filter { it.syncId in localMap }
        surviving.groupBy { DedupKey(it.title, it.dtStart, it.dtEnd, it.rrule) }.forEach { (_, group) ->
            if (group.size > 1) {
                val oldest = group.minByOrNull { it.createdAt }!!
                group.filter { it.id != oldest.id }.forEach {
                    appointmentRepository.softArchiveById(it.id!!, now)
                    dedupArchived = true
                }
            }
        }

        // Tombstone check: syncIds reported by phone that are explicitly deleted on the server.
        // Only covers personal appointments — shared-event tombstones are out of scope.
        val localSyncIds = localMap.keys.toList()
        val tombstoneSyncIds = if (localSyncIds.isEmpty()) emptySet()
            else appointmentRepository.findAllTombstonedPersonalByAccountNameAndSyncIdIn(accountName, localSyncIds)
                .map { it.syncId }.toSet()

        // Manifest is the source of truth for personal appointments:
        // if phone sent any entries OR explicitly confirmed empty, archive missing personal entries.
        val shouldArchiveMissingPersonal = localMap.isNotEmpty() || request.confirmedEmpty
        if (shouldArchiveMissingPersonal) {
            appointmentRepository.archiveOrphanedPersonalAppointments(accountName, localSyncIds, now)
            if (!invalidatedSlotCache) {
                slotService.invalidateAccount(accountName)
                invalidatedSlotCache = true
            }
        }

        // Re-read active nodes after possible runtime dedup / archive so response reflects current state.
        val currentPersonalNodes = appointmentRepository.findAllCurrentByAccountName(accountName)
        val currentOwnSharedNodes = appointmentRepository.findAllCurrentSharedByAccountName(accountName)
        val currentServerNodes = currentPersonalNodes + currentOwnSharedNodes
        val currentServerMap = currentServerNodes.associateBy { it.syncId }

        // Tombstoned syncIds are excluded from toUpload — don't re-upload explicitly deleted appointments.
        val toUpload = (localMap.keys - currentServerMap.keys - tombstoneSyncIds).toList()

        // Personal appointments: missing on phone → toDownload; server newer → toUpdate.
        val personalToDownload = currentPersonalNodes.filter { it.syncId !in localMap }.map { it.toDto() }
        val personalToUpdate = currentPersonalNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        // Shared calendar appointments from OTHER members — download to this phone (G4/G5 fix).
        val sharedCalendars = sharedCalendarRepository.findAllAccessibleByAccountName(accountName)
        val calendarIds = sharedCalendars.map { it.calendarId }
        val sharedCalendarAppointments = if (calendarIds.isEmpty()) emptyList()
            else appointmentRepository.findAllCurrentBySharedCalendarIds(calendarIds)
        val sharedToDownload = sharedCalendarAppointments
            .filter { it.syncId !in localMap && it.accountName != accountName }
            .map { it.toDto() }
        val sharedToUpdate = sharedCalendarAppointments.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.accountName != accountName && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        // Invalidate slot cache only when G3 dedup actually archived something.
        if (dedupArchived) {
            slotService.invalidateAccount(accountName)
        }

        return AppointmentManifest(
            toUpload = toUpload,
            toDownload = (personalToDownload + sharedToDownload).toMutableList(),
            toUpdate = (personalToUpdate + sharedToUpdate).toMutableList(),
            toDeleteLocally = tombstoneSyncIds.toList(),
        )
    }
}
