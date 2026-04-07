package de.sync.app.server.data

import jakarta.persistence.*

@Entity
@Table(name = "appointments")
data class AppointmentEntity(

    @Id
    @Column(name = "id", nullable = false)
    val id: String,

    @Column(name = "account_name", nullable = false)
    val accountName: String,

    @Column(name = "device_id", nullable = false)
    val deviceId: Long,

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    val title: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String? = null,

    @Column(name = "dt_start", nullable = false)
    val dtStart: Long,

    @Column(name = "dt_end")
    val dtEnd: Long? = null,

    @Column(name = "duration")
    val duration: String? = null,

    @Column(name = "all_day", nullable = false)
    val allDay: Boolean,

    @Column(name = "timezone", nullable = false, columnDefinition = "TEXT")
    val timezone: String,

    @Column(name = "rrule", columnDefinition = "TEXT")
    val rrule: String? = null,

    @Column(name = "location", columnDefinition = "TEXT")
    val location: String? = null,

    @Column(name = "organizer", columnDefinition = "TEXT")
    val organizer: String? = null,

    @Column(name = "calendar_name", columnDefinition = "TEXT")
    val calendarName: String? = null,

    @Column(name = "calendar_account_type")
    val calendarAccountType: String? = null,

    @Column(name = "calendar_account_name")
    val calendarAccountName: String? = null,

    @Column(name = "last_updated_at", nullable = false)
    val lastUpdatedAt: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long,
)

