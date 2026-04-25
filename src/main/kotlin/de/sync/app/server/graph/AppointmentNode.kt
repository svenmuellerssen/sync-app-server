package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("Appointment")
class AppointmentNode(
    @Id @GeneratedValue val id: Long? = null,

    // --- Version identity ---
    /** Stable appointment identity across all versions (same UUID for all versions of an appointment). */
    val syncId: String,
    /** Unique identity per version node — generated fresh for each new version. */
    val versionId: String = java.util.UUID.randomUUID().toString(),
    /** SHA-256 hash of all appointment fields. Used to detect unchanged uploads. */
    val contentHash: String = "",
    /**
     * Server-side calendarId (CalendarNode.calendarId or SharedCalendarNode.calendarId).
     * Denormalized for SlotService and history queries that don't need a calendar JOIN.
     * Null on legacy nodes migrated before CalendarNode was introduced.
     */
    val calendarId: String? = null,
    /** Server-assigned timestamp when this version node was created. Used for history ordering. */
    val versionCreatedAt: Long = System.currentTimeMillis(),
    /**
     * Set when the appointment is reconciled as deleted from the phone.
     * The node and all version history remain intact; only current-version queries filter this out.
     * Un-archived automatically when the phone re-syncs the same syncId.
     */
    val deletedAt: Long? = null,

    // --- Appointment data ---
    val accountName: String,
    val title: String,
    val description: String? = null,
    val dtStart: Long,
    val dtEnd: Long? = null,
    val duration: String? = null,
    val allDay: Boolean = false,
    val timezone: String,
    val rrule: String? = null,
    val location: String? = null,
    val organizer: String? = null,
    val calendarName: String? = null,
    val calendarAccountType: String? = null,
    val calendarAccountName: String? = null,
    val calendarColor: Int? = null,
    val status: String? = null,
    val lastUpdatedAt: Long,
    val createdAt: Long,

    @Relationship(type = "HAS_ATTENDEE", direction = OUTGOING)
    val attendees: MutableList<AttendeeNode> = mutableListOf(),

    @Relationship(type = "HAS_REMINDER", direction = OUTGOING)
    val reminders: MutableList<ReminderNode> = mutableListOf(),

    @Relationship(type = "HAS_MESSAGE", direction = OUTGOING)
    val messages: MutableList<MessageNode> = mutableListOf(),

    @Relationship(type = "BELONGS_TO_SHARED_CAL", direction = OUTGOING)
    val sharedCalendar: SharedCalendarNode? = null,

    @Relationship(type = "BELONGS_TO_GOOGLE_CAL", direction = OUTGOING)
    val googleCalendar: GoogleCalendarNode? = null,
) {
    // Identity is per version node, not per appointment
    override fun equals(other: Any?) = other is AppointmentNode && versionId == other.versionId
    override fun hashCode() = versionId.hashCode()
}

@Node("Reminder")
class ReminderNode(
    @Id @GeneratedValue val id: Long? = null,
    val minutes: Int,
    val method: Int = 1,
)

@Node("Attendee")
class AttendeeNode(
    @Id @GeneratedValue val id: Long? = null,
    val name: String? = null,
    val email: String? = null,
    val type: Int? = null,
    val status: Int? = null,
    @Relationship(type = "IS_CONTACT", direction = OUTGOING)
    val contact: ContactNode? = null,
) {
    override fun equals(other: Any?) = other is AttendeeNode && id == other.id
    override fun hashCode() = id?.hashCode() ?: 0
}

@Node("Message")
data class MessageNode(
    @Id @GeneratedValue val id: Long? = null,
    val messageId: String,
    val senderName: String,
    val text: String,
    val createdAt: Long,
)