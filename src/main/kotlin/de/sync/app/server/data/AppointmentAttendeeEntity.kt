package de.sync.app.server.data

import jakarta.persistence.*

@Entity
@Table(name = "appointment_attendees")
data class AppointmentAttendeeEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "appointment_id", nullable = false)
    val appointmentId: String,

    @Column(name = "name", columnDefinition = "TEXT")
    val name: String? = null,

    @Column(name = "email", columnDefinition = "TEXT")
    val email: String? = null,

    /** CalendarContract.Attendees.ATTENDEE_TYPE: 0=none, 1=required, 2=optional, 3=resource */
    @Column(name = "type")
    val type: Int? = null,

    /** CalendarContract.Attendees.ATTENDEE_STATUS: 0=none, 1=accepted, 2=declined, 3=invited, 4=tentative */
    @Column(name = "status")
    val status: Int? = null,

    /** LOOKUP_KEY of the matching contact (if any). NULL for external attendees without a local contact. */
    @Column(name = "contact_lookup_key")
    val contactLookupKey: String? = null,
)
