package de.sync.app.server

import de.sync.app.server.graph.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/appointments")
class AppointmentsController(
    private val appointmentRepository: AppointmentRepository,
    private val contactRepository: ContactRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
    private val googleCalendarRepository: GoogleCalendarRepository,
) {

    @GetMapping
    fun getAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentListResponse> {
        val accountName = request.getAttribute("accountName") as String
        val appointments = appointmentRepository.findAllByAccountName(accountName).map { it.toDto() }
        return ResponseEntity.ok(AppointmentListResponse(accountName = accountName, appointments = appointments))
    }

    @GetMapping("/count")
    fun getAppointmentCount(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentCountResponse> {
        val accountName = request.getAttribute("accountName") as String
        val count = appointmentRepository.countByAccountName(accountName)
        return ResponseEntity.ok(AppointmentCountResponse(accountName = accountName, count = count))
    }

    @Transactional
    @PostMapping
    fun uploadAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody @jakarta.validation.Valid batch: AppointmentBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentBackupResponse> {
        val accountName = request.getAttribute("accountName") as String
        val now = System.currentTimeMillis()
        var stored = 0

        for (dto in batch.appointments) {
            val existing = appointmentRepository.findBySyncId(dto.syncId)
            val createdAt = existing?.createdAt ?: now
            if (existing != null) appointmentRepository.deleteById(existing.id!!)

            val attendees = dto.attendees.map { a ->
                val contact = a.email?.let { contactRepository.findByAccountNameAndEmail(accountName, it) }
                    ?: a.contactLookupKey?.let { contactRepository.findByLookupKey(it) }
                AttendeeNode(name = a.name, email = a.email, type = a.type, status = a.status, contact = contact)
            }.toMutableList()

            val reminders = dto.reminders.map { r ->
                ReminderNode(minutes = r.minutes, method = r.method)
            }.toMutableList()

            // Resolve SharedCalendar or GoogleCalendar relationship
            val sharedCal: SharedCalendarNode? = if (dto.calendarAccountType == "de.sync.contacts" && dto.sharedCalendarId != null) {
                sharedCalendarRepository.findByCalendarId(dto.sharedCalendarId)
            } else null

            val googleCal: GoogleCalendarNode? = if (dto.calendarAccountType == "com.google" && dto.calendarId != null) {
                val cal = googleCalendarRepository.findByCalendarIdAndAccountName(dto.calendarId, accountName)
                    ?: GoogleCalendarNode(
                        calendarId = dto.calendarId,
                        displayName = dto.calendarName ?: dto.calendarId,
                        calendarAccountName = dto.calendarAccountName ?: "",
                        color = dto.calendarColor,
                        accessLevel = dto.accessLevel,
                        accountName = accountName,
                    )
                // Populate HAS_MEMBER from attendee emails
                val emails = attendees.mapNotNull { it.email }.toSet()
                for (email in emails) {
                    val contact = contactRepository.findByAccountNameAndEmail(accountName, email)
                    if (contact != null && cal.members.none { it.syncId == contact.syncId }) {
                        cal.members.add(contact)
                    }
                }
                googleCalendarRepository.save(cal)
            } else null

            val node = AppointmentNode(
                syncId = dto.syncId,
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
                createdAt = createdAt,
                attendees = attendees,
                reminders = reminders,
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

data class ReminderDto(
    val minutes: Int,
    val method: Int = 1,
)

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
    val calendarId: String? = null,         // device-local calendar ID as string
    val sharedCalendarId: String? = null,
    val accessLevel: Int? = null,
    val status: Int? = null,                // 0=TENTATIVE, 1=CONFIRMED, 2=CANCELLED
    val attendees: List<AttendeeDto> = emptyList(),
    val reminders: List<ReminderDto> = emptyList(),
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
    val status: Int? = null,
    val attendees: List<AttendeeDto>,
    val reminders: List<ReminderDto>,
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
    status = status?.toIntOrNull(),
    attendees = attendees.map {
        AttendeeDto(
            name = it.name,
            email = it.email,
            type = it.type,
            status = it.status,
            contactLookupKey = it.contact?.lookupKey,
        )
    },
    reminders = reminders.map { ReminderDto(minutes = it.minutes, method = it.method) },
    lastUpdatedAt = lastUpdatedAt,
)