package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("Appointment")
class AppointmentNode(
    @Id @GeneratedValue val id: Long? = null,
    val syncId: String,
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

    @Relationship(type = "HAS_MESSAGE", direction = OUTGOING)
    val messages: MutableList<MessageNode> = mutableListOf(),

    @Relationship(type = "BELONGS_TO_SHARED_CAL", direction = OUTGOING)
    val sharedCalendar: SharedCalendarNode? = null,

    @Relationship(type = "BELONGS_TO_GOOGLE_CAL", direction = OUTGOING)
    val googleCalendar: GoogleCalendarNode? = null,
) {
    override fun equals(other: Any?) = other is AppointmentNode && syncId == other.syncId
    override fun hashCode() = syncId.hashCode()
}

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