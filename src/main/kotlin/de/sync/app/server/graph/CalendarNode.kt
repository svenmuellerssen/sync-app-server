package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

/**
 * Represents a personal calendar owned by an account.
 * calendarType: LOCAL (personal on-device) | GOOGLE (read-only mirror, excluded from sync)
 *
 * Relationship pattern:
 *   (:Account)-[:OWNS_CALENDAR]->(:CalendarNode)-[:HAS_APPOINTMENT]->(:Appointment)
 *
 * HAS_APPOINTMENT edges are managed exclusively via CalendarRepository Cypher queries,
 * not via an entity collection, to avoid eager-loading thousands of appointments.
 */
@Node("CalendarNode")
class CalendarNode(
    @Id @GeneratedValue val id: Long? = null,

    /** Server-generated UUID. Written to CalendarContract.Calendars._SYNC_ID on the device. */
    val calendarId: String,

    val name: String,
    val color: Int? = null,

    /** LOCAL | GOOGLE */
    val calendarType: String,

    /** Denormalized owner — avoids a JOIN in most queries. */
    val accountName: String,

    /** Null = active. Non-null = soft-deleted (Unix ms). */
    val deletedAt: Long? = null,
)
