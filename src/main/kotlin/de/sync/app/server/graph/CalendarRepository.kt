package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface CalendarRepository : Neo4jRepository<CalendarNode, Long> {

    @Query("MATCH (cal:CalendarNode {calendarId: \$calendarId}) WHERE cal.deletedAt IS NULL RETURN cal")
    fun findByCalendarId(calendarId: String): CalendarNode?

    @Query("MATCH (cal:CalendarNode {accountName: \$accountName}) WHERE cal.deletedAt IS NULL RETURN cal")
    fun findAllActiveByAccountName(accountName: String): List<CalendarNode>

    @Query("""
        MATCH (cal:CalendarNode {name: ${'$'}name, accountName: ${'$'}accountName, calendarType: ${'$'}calendarType})
        WHERE cal.deletedAt IS NULL
        RETURN cal
    """)
    fun findByNameAndAccountNameAndCalendarType(
        name: String,
        accountName: String,
        calendarType: String,
    ): CalendarNode?

    /**
     * Atomically find-or-create a CalendarNode by natural key (name, accountName, calendarType).
     * ON CREATE: sets calendarId and color.
     * ON MATCH: clears deletedAt (reactivates a previously soft-deleted calendar with the same name).
     * Returns the existing or newly created node.
     *
     * Use the returned calendarId to detect whether a new node was created:
     * if (cal.calendarId == newUuid) → newly created, record for response.
     */
    @Query("""
        MERGE (cal:CalendarNode {name: ${'$'}name, accountName: ${'$'}accountName, calendarType: ${'$'}calendarType})
        ON CREATE SET cal.calendarId = ${'$'}calendarId, cal.color = ${'$'}color
        ON MATCH SET cal.deletedAt = null
        RETURN cal
    """)
    fun mergeByNaturalKey(
        name: String,
        accountName: String,
        calendarType: String,
        calendarId: String,
        color: Int?,
    ): CalendarNode

    /**
     * Creates a HAS_APPOINTMENT edge from a CalendarNode to an AppointmentNode.
     * Used when a new (or first) version of a personal appointment is saved.
     */
    @Query("""
        MATCH (cal:CalendarNode {calendarId: ${'$'}calendarId}), (a:Appointment)
        WHERE id(a) = ${'$'}appointmentId
        MERGE (cal)-[:HAS_APPOINTMENT]->(a)
    """)
    fun addHasAppointmentEdge(calendarId: String, appointmentId: Long)

    /**
     * Finds the CalendarNode that currently points to the given appointment via HAS_APPOINTMENT.
     * Returns null if the appointment is not current (already archived).
     */
    @Query("MATCH (cal:CalendarNode)-[:HAS_APPOINTMENT]->(a:Appointment) WHERE id(a) = \$appointmentId RETURN cal")
    fun findByCurrentAppointmentId(appointmentId: Long): CalendarNode?
}
