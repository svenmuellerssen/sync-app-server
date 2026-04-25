package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("Booking")
class BookingNode(
    @Id @GeneratedValue val id: Long? = null,

    /** Stable public UUID — used by app for local event binding. */
    val bookingId: String? = null,

    val accountName: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val locationName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** Null = active. Non-null = soft-deleted (Unix ms). */
    val cancelledAt: Long? = null,

    @Relationship(type = "HAS_INVITEE", direction = OUTGOING)
    val invitees: MutableSet<ContactNode> = linkedSetOf(),

    @Relationship(type = "HAS_MESSAGE", direction = OUTGOING)
    val messages: MutableList<BookingMessageNode> = mutableListOf(),

    @Relationship(type = "BELONGS_TO_SHARED_CAL", direction = OUTGOING)
    val sharedCalendar: SharedCalendarNode? = null,

    /** Account that created this booking. */
    @Relationship(type = "CREATED_BY", direction = OUTGOING)
    val creator: AccountNode? = null,
)

@Node("BookingMessage")
data class BookingMessageNode(
    @Id @GeneratedValue val id: Long? = null,
    val senderName: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Null = never edited. Non-null = last edit timestamp (Unix ms). */
    val editedAt: Long? = null,
)
