package de.sync.app.server

import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.*
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/appointments")
class AppointmentsController(
    private val appointmentRepository: AppointmentRepository,
    private val contactRepository: ContactRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val googleCalendarRepository: GoogleCalendarRepository,
    private val sessionRepository: SessionRepository,
) {

    @GetMapping
    fun getAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<AppointmentListResponse> {
        if (!sessionRepository.findById(token).isPresent) return ResponseEntity.status(401).build()
        val appointments = appointmentRepository.findAllByAccountName(accountName).map { it.toDto() }
        return ResponseEntity.ok(AppointmentListResponse(accountName = accountName, appointments = appointments))
    }

    @GetMapping("/count")
    fun getAppointmentCount(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<AppointmentCountResponse> {
        if (!sessionRepository.findById(token).isPresent) return ResponseEntity.status(401).build()
        val count = appointmentRepository.countByAccountName(accountName)
        return ResponseEntity.ok(AppointmentCountResponse(accountName = accountName, count = count))
    }

    @Transactional
    @PostMapping
    fun uploadAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody batch: AppointmentBatchRequest,
    ): ResponseEntity<AppointmentBackupResponse> {
        if (!sessionRepository.findById(token).isPresent) return ResponseEntity.status(401).build()
        val now = System.currentTimeMillis()
        var stored = 0

        for (dto in batch.appointments) {
            val existing = appointmentRepository.findBySyncId(dto.syncId)
            val createdAt = existing?.createdAt ?: now
            if (existing != null) appointmentRepository.deleteById(existing.id!!)

            val attendees = dto.attendees.map { a ->
                val contact = a.email?.let { contactRepository.findByAccountNameAndEmail(batch.accountName, it) }
                    ?: a.contactLookupKey?.let { contactRepository.findByLookupKey(it) }
                AttendeeNode(name = a.name, email = a.email, type = a.type, status = a.status, contact = contact)
            }.toMutableList()

            // Resolve SharedCalendar or GoogleCalendar relationship
            val sharedCal: SharedCalendarNode? = if (dto.calendarAccountType == "de.sync.contacts" && dto.sharedCalendarId != null) {
                sharedCalendarRepository.findByCalendarId(dto.sharedCalendarId)
            } else null

            val googleCal: GoogleCalendarNode? = if (dto.calendarAccountType == "com.google" && dto.calendarId != null) {
                val cal = googleCalendarRepository.findByCalendarIdAndAccountName(dto.calendarId, batch.accountName)
                    ?: GoogleCalendarNode(
                        calendarId = dto.calendarId,
                        displayName = dto.calendarName ?: dto.calendarId,
                        calendarAccountName = dto.calendarAccountName ?: "",
                        color = dto.calendarColor,
                        accessLevel = dto.accessLevel,
                        accountName = batch.accountName,
                    )
                // Populate HAS_MEMBER from attendee emails
                val emails = attendees.mapNotNull { it.email }.toSet()
                for (email in emails) {
                    val contact = contactRepository.findByAccountNameAndEmail(batch.accountName, email)
                    if (contact != null && cal.members.none { it.syncId == contact.syncId }) {
                        cal.members.add(contact)
                    }
                }
                googleCalendarRepository.save(cal)
            } else null

            val node = AppointmentNode(
                syncId = dto.syncId,
                accountName = batch.accountName,
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
                lastUpdatedAt = dto.lastUpdatedAt,
                createdAt = createdAt,
                attendees = attendees,
                sharedCalendar = sharedCal,
                googleCalendar = googleCal,
            )

            appointmentRepository.save(node)
            stored++
        }

        val revision = UUID.randomUUID().toString()
        return ResponseEntity.ok(AppointmentBackupResponse(revision = revision, appointmentsStored = stored))
    }
}

data class AttendeeDto(
    val name: String? = null,
    val email: String? = null,
    val type: Int? = null,
    val status: Int? = null,
    val contactLookupKey: String? = null,
)

data class AppointmentBatchRequest(
    val accountName: String = "",
    val appointments: List<AppointmentDtoRequest> = emptyList(),
)

data class AppointmentDtoRequest(
    val syncId: String,
    val title: String,
    val description: String? = null,
    val dtStart: Long,
    val dtEnd: Long? = null,
    val duration: String? = null,
    val allDay: Boolean,
    val timezone: String,
    val rrule: String? = null,
    val location: String? = null,
    val organizer: String? = null,
    val calendarName: String? = null,
    val calendarAccountType: String? = null,
    val calendarAccountName: String? = null,
    val calendarColor: Int? = null,
    val calendarId: String? = null,         // for Google/SharedCal lookup
    val sharedCalendarId: String? = null,   // for de.sync.contacts calendars
    val accessLevel: Int? = null,
    val attendees: List<AttendeeDto> = emptyList(),
    val lastUpdatedAt: Long,
)

data class AppointmentBackupResponse(val revision: String, val appointmentsStored: Int)
data class AppointmentCountResponse(val accountName: String, val count: Long)
data class AppointmentListResponse(val accountName: String, val appointments: List<AppointmentDtoResponse>)

data class AppointmentDtoResponse(
    val syncId: String,
    val title: String,
    val description: String?,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timezone: String,
    val rrule: String?,
    val location: String?,
    val organizer: String?,
    val calendarName: String?,
    val calendarAccountType: String?,
    val calendarAccountName: String?,
    val calendarColor: Int?,
    val sharedCalendarId: String? = null,
    val googleCalendarId: String? = null,
    val attendees: List<AttendeeDto>,
    val lastUpdatedAt: Long,
)

internal fun AppointmentNode.toDto() = AppointmentDtoResponse(
    syncId = syncId,
    title = title,
    description = description,
    dtStart = dtStart,
    dtEnd = dtEnd,
    duration = duration,
    allDay = allDay,
    timezone = timezone,
    rrule = rrule,
    location = location,
    organizer = organizer,
    calendarName = calendarName,
    calendarAccountType = calendarAccountType,
    calendarAccountName = calendarAccountName,
    calendarColor = calendarColor,
    sharedCalendarId = sharedCalendar?.calendarId,
    googleCalendarId = googleCalendar?.calendarId,
    attendees = attendees.map {
        AttendeeDto(
            name = it.name,
            email = it.email,
            type = it.type,
            status = it.status,
            contactLookupKey = it.contact?.lookupKey,
        )
    },
    lastUpdatedAt = lastUpdatedAt,
)