package de.sync.app.server

import de.sync.app.server.graph.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

@RestController
@RequestMapping("/appointments")
class AppointmentsController(
    private val appointmentRepository: AppointmentRepository,
    private val appointmentService: AppointmentService,
) {

    @GetMapping
    fun getAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentListResponse> {
        val accountName = request.getAttribute("accountName") as String
        val appointments = appointmentRepository.findAllCurrentByAccountName(accountName).map { it.toDto() }
        return ResponseEntity.ok(AppointmentListResponse(accountName = accountName, appointments = appointments))
    }

    @GetMapping("/count")
    fun getAppointmentCount(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentCountResponse> {
        val accountName = request.getAttribute("accountName") as String
        val count = appointmentRepository.countAllCurrentByAccountName(accountName)
        return ResponseEntity.ok(AppointmentCountResponse(accountName = accountName, count = count))
    }

    @GetMapping("/{syncId}/history")
    fun getAppointmentHistory(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable syncId: String,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentHistoryResponse> {
        val accountName = request.getAttribute("accountName") as String
        val versions = appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc(syncId)
            .filter { it.accountName == accountName }
            .map { it.toDto() }
        return ResponseEntity.ok(AppointmentHistoryResponse(syncId = syncId, versions = versions))
    }

    @PostMapping
    fun uploadAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody @jakarta.validation.Valid batch: AppointmentBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AppointmentBackupResponse> {
        val accountName = request.getAttribute("accountName") as String
        val result = appointmentService.processBatch(batch.appointments, accountName)
        return ResponseEntity.ok(
            AppointmentBackupResponse(
                appointmentsStored = result.stored,
                skipped = result.skipped,
                newCalendars = result.newCalendars.map { it.toDto() },
            )
        )
    }

    @DeleteMapping("/{syncId}")
    fun deleteAppointment(
        @RequestHeader("X-Sync-Token") token: String,
        @PathVariable syncId: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val accountName = request.getAttribute("accountName") as String
        return if (appointmentService.deleteByExplicit(syncId, accountName))
            ResponseEntity.noContent().build()
        else
            ResponseEntity.notFound().build()
    }
}

// ---------------------------------------------------------------------------
// Hash function
// ---------------------------------------------------------------------------

internal fun hashAppointment(dto: AppointmentDtoRequest): String {
    val sb = StringBuilder()
    fun append(value: Any?) { sb.append('|').append(value ?: "") }

    append(dto.syncId)
    append(dto.title)
    append(dto.description)
    append(dto.dtStart)
    append(dto.dtEnd)
    append(dto.duration)
    append(dto.allDay)
    append(dto.timezone)
    append(dto.rrule)
    append(dto.location)
    append(dto.organizer)
    append(dto.calendarName)
    append(dto.calendarAccountType)
    append(dto.calendarAccountName)
    append(dto.calendarColor)
    append(dto.calendarId)
    append(dto.sharedCalendarId)
    append(dto.sharedEventOwnerAccount)
    append(dto.accessLevel)
    append(dto.status)
    // lastUpdatedAt intentionally excluded: CalendarReader sets it to System.currentTimeMillis()
    // on every sync, which would change the hash even when nothing changed. Content dedup must
    // be based on actual appointment data only.

    dto.attendees.sortedWith(compareBy({ it.email }, { it.name })).forEach { a ->
        sb.append("|ATT:").append(a.email ?: "").append(':')
            .append(a.name ?: "").append(':').append(a.type ?: "").append(':').append(a.status ?: "")
    }
    dto.reminders.sortedBy { it.minutes }.forEach { r ->
        sb.append("|REM:").append(r.minutes).append(':').append(r.method)
    }

    return MessageDigest.getInstance("SHA-256")
        .digest(sb.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------
// DTOs
// ---------------------------------------------------------------------------

data class ReminderDto(val minutes: Int, val method: Int = 1)

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
    /** Device-local Android calendar ID. Used for Google Calendar metadata (NOT the server CalendarNode ID). */
    val calendarId: String? = null,
    /**
     * Server-assigned CalendarNode UUID (written to CalendarContract._SYNC_ID after first sync).
     * Null on first sync — server will find-or-create a CalendarNode by natural key.
     */
    val serverCalendarId: String? = null,
    val sharedCalendarId: String? = null,
    val sharedEventOwnerAccount: String? = null,
    val accessLevel: Int? = null,
    val status: Int? = null,
    val attendees: List<AttendeeDto> = emptyList(),
    val reminders: List<ReminderDto> = emptyList(),
    val lastUpdatedAt: Long,
)

data class NewCalendarDto(
    /** Server-generated UUID — app writes this to CalendarContract.Calendars._SYNC_ID. */
    val serverCalendarId: String,
    val name: String,
    val calendarType: String,
)

data class AppointmentBackupResponse(
    val appointmentsStored: Int,
    val skipped: Int = 0,
    /**
     * CalendarNodes created during this upload (bootstrap sync).
     * For each entry: app should write serverCalendarId into CalendarContract._SYNC_ID
     * so subsequent syncs can skip the name-based lookup.
     */
    val newCalendars: List<NewCalendarDto> = emptyList(),
)

data class AppointmentCountResponse(val accountName: String, val count: Long)
data class AppointmentListResponse(val accountName: String, val appointments: List<AppointmentDtoResponse>)
data class AppointmentHistoryResponse(val syncId: String, val versions: List<AppointmentDtoResponse>)

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
    val sharedEventOwnerAccount: String? = null,
    val googleCalendarId: String? = null,
    val status: Int? = null,
    val attendees: List<AttendeeDto>,
    val reminders: List<ReminderDto>,
    val lastUpdatedAt: Long,
    val versionCreatedAt: Long? = null,
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
    sharedEventOwnerAccount = accountName,
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
    versionCreatedAt = versionCreatedAt,
)

internal fun CalendarNode.toDto() = NewCalendarDto(
    serverCalendarId = calendarId,
    name = name,
    calendarType = calendarType,
)

