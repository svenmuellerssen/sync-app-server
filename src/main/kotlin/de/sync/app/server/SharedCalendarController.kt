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
    private val accountRepository: AccountRepository,
    private val sessionRepository: SessionRepository,
    private val googleCalendarRepository: GoogleCalendarRepository,
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
            members = mutableListOf(account),
        )
        val saved = sharedCalendarRepository.save(node)
        return ResponseEntity.ok(saved.toDto())
    }

    @GetMapping("/shared-calendar/list")
    fun listSharedCalendars(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<List<SharedCalendarDto>> {
        if (!sessionRepository.findById(token).isPresent) return ResponseEntity.status(401).build()
        val cals = sharedCalendarRepository.findAllByMemberAccountName(accountName)
        return ResponseEntity.ok(cals.map { it.toDto() })
    }

    @GetMapping("/shared-calendar/invite/{calendarId}")
    fun generateInvite(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable calendarId: String,
    ): ResponseEntity<InviteCodeDto> {
        val accountName = resolveAccount(token) ?: return ResponseEntity.status(401).build()
        sharedCalendarRepository.findByCalendarId(calendarId) ?: return ResponseEntity.notFound().build()

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

        val cal = sharedCalendarRepository.findByCalendarId(invite.calendarId)
            ?: return ResponseEntity.status(404).build()
        val account = accountRepository.findByUsername(accountName) ?: return ResponseEntity.status(401).build()

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
        val cal = sharedCalendarRepository.findByCalendarId(calendarId) ?: return ResponseEntity.notFound().build()
        cal.members.removeIf { it.username == accountName }
        sharedCalendarRepository.save(cal)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/calendar/google")
    fun getGoogleCalendars(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<List<GoogleCalendarDto>> {
        if (!sessionRepository.findById(token).isPresent) return ResponseEntity.status(401).build()
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
    memberCount = members.size,
    members = members.map { it.username },
)
