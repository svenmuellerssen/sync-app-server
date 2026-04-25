package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.INCOMING
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

/**
 * A calendar shared between multiple accounts.
 *
 * Relationship pattern:
 *   (:Account)-[:OWNS_CALENDAR]->(:SharedCalendar)   ← creator (exactly one)
 *   (:Account)-[:MEMBER_OF]->(:SharedCalendar)        ← non-creator members
 *   (:SharedCalendar)-[:HAS_APPOINTMENT]->(:Appointment) ← current appointments
 *
 * HAS_APPOINTMENT edges are managed via AppointmentRepository Cypher queries.
 */
@Node("SharedCalendar")
class SharedCalendarNode(
    @Id @GeneratedValue val id: Long? = null,
    val calendarId: String,
    val name: String,
    val color: Int = 0xFF4CAF50.toInt(),
    val createdAt: Long = System.currentTimeMillis(),

    /** Denormalized creator username for display and ownership checks. */
    val createdBy: String,

    /** Null = active. Non-null = soft-deleted (Unix ms). */
    val deletedAt: Long? = null,

    /** The account that created this calendar (OWNS_CALENDAR, INCOMING from Account). */
    @Relationship(type = "OWNS_CALENDAR", direction = INCOMING)
    val owner: AccountNode? = null,

    /** Non-creator members (MEMBER_OF, INCOMING from Account). */
    @Relationship(type = "MEMBER_OF", direction = INCOMING)
    val members: MutableList<AccountNode> = mutableListOf(),
)

