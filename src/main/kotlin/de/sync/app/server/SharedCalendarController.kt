package de.sync.app.server

import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.cache.SharedCalendarInviteEntity
import de.sync.app.server.cache.SharedCalendarInviteRepository
import de.sync.app.server.graph.*
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateSharedCalendarRequest(val name: String, val color: Int? = null)
data class JoinSharedCalendarRequest(val inviteCode: String)
data class SharedCalendarDto(
    val calendarId: String,
    val name: String,
    val color: Int,
    val createdBy: String,
    val memberCount: Int,
    val members: List<String>,
)
data class InviteCodeDto(val code: String, val expiresInSeconds: Int, val calendarId: String)

data class PersonalCalendarDto(
    /** Server-generated UUID stored in CalendarContract.Calendars._SYNC_ID on the device. */
    val serverCalendarId: String,
    val name: String,
    /** LOCAL | GOOGLE */
    val calendarType: String,
    val color: Int?,
)

data class GoogleCalendarDto(
    val calendarId: String,
    val displayName: String,
    val calendarAccountName: String,
    val color: Int?,
    val accessLevel: Int?,
    val members: List<String>,
)

@RestController
class SharedCalendarController(
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val sharedCalendarInviteRepository: SharedCalendarInviteRepository,
    private val bookingRepository: BookingRepository,
    private val accountRepository: AccountRepository,
    private val sessionRepository: SessionRepository,
    private val googleCalendarRepository: GoogleCalendarRepository,
    private val calendarRepository: CalendarRepository,
    private val slotService: SlotService,
) {

    private fun resolveAccount(token: String): String? {
        return sessionRepository.findById(token).orElse(null)?.accountName
    }

    @PostMapping("/shared-calendar")
    @Transactional
    fun createSharedCalendar(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody request: CreateSharedCalendarRequest,
    ): ResponseEntity<SharedCalendarDto> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val account = accountRepository.findByUsername(accountName) ?: return ResponseEntity.status(401).build()

        val node = SharedCalendarNode(
            calendarId = UUID.randomUUID().toString(),
            name = request.name,
            color = request.color ?: 0xFF4CAF50.toInt(),
            createdBy = accountName,
            owner = account,
            members = mutableListOf(),
        )
        val saved = sharedCalendarRepository.save(node)
        return ResponseEntity.ok(saved.toDto())
    }

    @GetMapping("/shared-calendar/list")
    fun listSharedCalendars(
        @RequestHeader("X-Sync-Token") token: String,
    ): ResponseEntity<List<SharedCalendarDto>> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cals = sharedCalendarRepository.findAllAccessibleByAccountName(accountName)
        return ResponseEntity.ok(cals.map { it.toDto() })
    }

    @GetMapping("/shared-calendar/invite/{calendarId}")
    fun generateInvite(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable calendarId: String,
    ): ResponseEntity<InviteCodeDto> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cal = sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull(calendarId) ?: return ResponseEntity.notFound().build()
        if (cal.createdBy != accountName)
            return ResponseEntity.status(403).build()

        val code = (1..7).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        sharedCalendarInviteRepository.save(
            SharedCalendarInviteEntity(inviteCode = code, calendarId = calendarId, createdBy = accountName)
        )
        return ResponseEntity.ok(InviteCodeDto(code = code, expiresInSeconds = 600, calendarId = calendarId))
    }

    @PostMapping("/shared-calendar/join")
    @Transactional
    fun joinSharedCalendar(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody request: JoinSharedCalendarRequest,
    ): ResponseEntity<SharedCalendarDto> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val invite = sharedCalendarInviteRepository.findById(request.inviteCode).orElse(null)
            ?: return ResponseEntity.status(404).build()

        val cal = sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull(invite.calendarId)
            ?: return ResponseEntity.status(404).build()
        val account = accountRepository.findByUsername(accountName) ?: return ResponseEntity.status(401).build()

        if (cal.createdBy == accountName) {
            // Owner rejoining via invite code — skip, but consume the code
            sharedCalendarInviteRepository.deleteById(request.inviteCode)
            return ResponseEntity.ok(cal.toDto())
        }
        if (cal.members.none { it.username == accountName }) {
            cal.members.add(account)
            sharedCalendarRepository.save(cal)
        }
        sharedCalendarInviteRepository.deleteById(request.inviteCode)
        return ResponseEntity.ok(cal.toDto())
    }

    @DeleteMapping("/shared-calendar/{calendarId}/leave")
    @Transactional
    fun leaveSharedCalendar(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable calendarId: String,
    ): ResponseEntity<Void> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cal = sharedCalendarRepository.findByCalendarIdAndDeletedAtIsNull(calendarId) ?: return ResponseEntity.notFound().build()
        if (cal.createdBy == accountName) return ResponseEntity.status(409).build()

        // Snapshot all affected accounts BEFORE removing the member so the leaving
        // account is still included in the invalidation set.
        val affectedAccounts = sharedCalendarRepository
            .findMemberUsernamesByCalendarIds(listOf(calendarId))
            .toSet()

        cal.members.removeIf { it.username == accountName }
        sharedCalendarRepository.save(cal)

        // Invalidate slot cache for the leaving account and all remaining members/owner.
        // All of them may have had their slot calculations influenced by shared appointments
        // from the leaving member's calendar visibility.
        for (username in affectedAccounts) {
            slotService.invalidateAccount(username)
        }

        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/shared-calendar/{calendarId}")
    @Transactional
    fun deleteSharedCalendar(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable calendarId: String,
    ): ResponseEntity<Void> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cal = sharedCalendarRepository.findByCalendarId(calendarId) ?: return ResponseEntity.notFound().build()
        if (cal.createdBy != accountName) return ResponseEntity.status(403).build()

        val now = System.currentTimeMillis()

        // Clean up pending invite codes so the calendar can't be joined after deletion
        sharedCalendarInviteRepository.findAllByCalendarId(calendarId).forEach { invite ->
            sharedCalendarInviteRepository.deleteById(invite.inviteCode)
        }

        // Snapshot all affected accounts BEFORE soft-deleting the calendar,
        // because findMemberUsernamesByCalendarIds filters on sc.deletedAt IS NULL.
        val affectedAccounts = sharedCalendarRepository
            .findMemberUsernamesByCalendarIds(listOf(calendarId))
            .toSet() + accountName

        // Close the door first: no new bookings can be created after this point.
        sharedCalendarRepository.softDelete(calendarId, now)

        // Cascade soft-delete to all active bookings in this calendar.
        bookingRepository.softDeleteAllBySharedCalendarId(calendarId, now)

        // Invalidate slot cache for all members, not just the owner.
        for (username in affectedAccounts) {
            slotService.invalidateAccount(username)
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * Returns all active personal CalendarNodes for the account.
     * The app uses this before uploading to determine which serverCalendarId to send per appointment.
     * On first sync the list is empty; the server creates CalendarNodes during POST /appointments
     * and returns them in newCalendars. On subsequent syncs the app reads _SYNC_ID and sends
     * serverCalendarId directly — no GET /calendar call needed.
     */
    @GetMapping("/calendar")
    fun getPersonalCalendars(
        @RequestHeader("X-Sync-Token") token: String,
    ): ResponseEntity<List<PersonalCalendarDto>> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cals = calendarRepository.findAllActiveByAccountName(accountName)
        return ResponseEntity.ok(cals.map {
            PersonalCalendarDto(serverCalendarId = it.calendarId, name = it.name, calendarType = it.calendarType, color = it.color)
        })
    }

    @GetMapping("/calendar/google")
    fun getGoogleCalendars(
        @RequestHeader("X-Sync-Token") token: String,
    ): ResponseEntity<List<GoogleCalendarDto>> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        val cals = googleCalendarRepository.findAllByAccountName(accountName)
        return ResponseEntity.ok(cals.map { gc ->
            GoogleCalendarDto(
                calendarId = gc.calendarId,
                displayName = gc.displayName,
                calendarAccountName = gc.calendarAccountName,
                color = gc.color,
                accessLevel = gc.accessLevel,
                members = gc.members.mapNotNull { it.displayName },
            )
        })
    }
}

private fun SharedCalendarNode.toDto() = SharedCalendarDto(
    calendarId = calendarId,
    name = name,
    color = color,
    createdBy = createdBy,
    memberCount = members.size + (if (owner != null) 1 else 0),
    members = (listOfNotNull(owner?.username) + members.map { it.username }).distinct(),
)
