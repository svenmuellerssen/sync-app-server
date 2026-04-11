package de.sync.app.server

import de.sync.app.server.data.AppointmentAttendeeEntity
import de.sync.app.server.data.AppointmentEntity
import de.sync.app.server.data.AppointmentRepository
import jakarta.transaction.Transactional
import org.springframework.http.ResponseEntity
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
class AppointmentsController(private val appointmentRepository: AppointmentRepository) {

    @Transactional
    @GetMapping
    fun getAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<AppointmentListResponse> {
        val appointments = appointmentRepository.findAllByAccountName(accountName).map { it.toDto() }
        return ResponseEntity.ok(AppointmentListResponse(accountName = accountName, appointments = appointments))
    }

    @Transactional
    @GetMapping("/count")
    fun getAppointmentCount(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestParam accountName: String,
    ): ResponseEntity<AppointmentCountResponse> {
        val count = appointmentRepository.countByAccountName(accountName)
        return ResponseEntity.ok(AppointmentCountResponse(accountName = accountName, count = count))
    }

    @Transactional
    @PostMapping
    fun uploadAppointments(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody batch: AppointmentBatchRequest,
    ): ResponseEntity<AppointmentBackupResponse> {
        val now = System.currentTimeMillis()
        var stored = 0

        for (dto in batch.appointments) {
            val existing = appointmentRepository.findAllByAccountNameAndDeviceId(batch.accountName, dto.deviceId)
            val firstCreatedAt = existing.minOfOrNull { it.createdAt }

            existing.forEach { appointmentRepository.deleteById(it.id) }

            val entity = AppointmentEntity(
                id = UUID.randomUUID().toString(),
                accountName = batch.accountName,
                deviceId = dto.deviceId,
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
                calendarName        = dto.calendarName,
                calendarAccountType = dto.calendarAccountType,
                calendarAccountName = dto.calendarAccountName,
                lastUpdatedAt = dto.lastUpdatedAt,
                createdAt = firstCreatedAt ?: now,
            )

            entity.attendees.addAll(dto.attendees.map { a ->
                AppointmentAttendeeEntity(
                    appointmentId    = entity.id,
                    name             = a.name,
                    email            = a.email,
                    type             = a.type,
                    status           = a.status,
                    contactLookupKey = a.contactLookupKey,
                )
            })

            appointmentRepository.save(entity)
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
    val deviceId: Long,
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
    val attendees: List<AttendeeDto> = emptyList(),
    val lastUpdatedAt: Long,
)

data class AppointmentBackupResponse(val revision: String, val appointmentsStored: Int)
data class AppointmentCountResponse(val accountName: String, val count: Long)
data class AppointmentListResponse(val accountName: String, val appointments: List<AppointmentDtoResponse>)

data class AppointmentDtoResponse(
    val deviceId: Long,
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
    val attendees: List<AttendeeDto>,
    val lastUpdatedAt: Long,
)

private fun AppointmentEntity.toDto() = AppointmentDtoResponse(
    deviceId = deviceId,
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
    calendarName        = calendarName,
    calendarAccountType = calendarAccountType,
    calendarAccountName = calendarAccountName,
    attendees = attendees.map { AttendeeDto(name = it.name, email = it.email, type = it.type, status = it.status, contactLookupKey = it.contactLookupKey) },
    lastUpdatedAt       = lastUpdatedAt,
)

