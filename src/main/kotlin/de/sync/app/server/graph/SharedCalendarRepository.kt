package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface SharedCalendarRepository : Neo4jRepository<SharedCalendarNode, Long> {

    /** Custom Cypher — loads only node properties, NOT relationships. Use only for display/delete-check. */
    @Query("MATCH (sc:SharedCalendar {calendarId: \$calendarId}) WHERE sc.deletedAt IS NULL RETURN sc")
    fun findByCalendarId(calendarId: String): SharedCalendarNode?

    /**
     * Derived query — SDN 6 eager-loads owner + members relationships automatically.
     * Use this wherever owner/members access is needed (invite, join, leave).
     */
    fun findByCalendarIdAndDeletedAtIsNull(calendarId: String): SharedCalendarNode?

    @Query("MATCH (sc:SharedCalendar {calendarId: \$calendarId}) SET sc.deletedAt = \$deletedAt")
    fun softDelete(calendarId: String, deletedAt: Long)

    /**
     * Adds an account as a member (MEMBER_OF) of the given shared calendar.
     * Idempotent — uses MERGE so calling twice has no effect.
     */
    @Query("""
        MATCH (a:Account {username: ${'$'}username})
        MATCH (sc:SharedCalendar {calendarId: ${'$'}calendarId})
        MERGE (a)-[:MEMBER_OF]->(sc)
    """)
    fun addMemberByUsername(calendarId: String, username: String)

    /**
     * Returns all shared calendars accessible to an account (owner via OWNS_CALENDAR + members via MEMBER_OF).
     */
    @Query("MATCH (:Account {username: \$accountName})-[:OWNS_CALENDAR|MEMBER_OF]->(sc:SharedCalendar) WHERE sc.deletedAt IS NULL RETURN sc")
    fun findAllAccessibleByAccountName(accountName: String): List<SharedCalendarNode>

    /**
     * Returns all account usernames (owner + members) for the given active shared calendars.
     * Used exclusively for slot-cache invalidation — returns only scalars, no entity loading.
     */
    @Query("""
        MATCH (sc:SharedCalendar)
        WHERE sc.calendarId IN ${'$'}calendarIds AND sc.deletedAt IS NULL
        MATCH (u:Account)-[:OWNS_CALENDAR|MEMBER_OF]->(sc)
        RETURN DISTINCT u.username
    """)
    fun findMemberUsernamesByCalendarIds(calendarIds: Collection<String>): List<String>

    /**
     * Returns 1 if the account is owner or member of the given active SharedCalendar, 0 otherwise.
     * Used by BookingController to check read access for SharedCalendar members.
     */
    @Query("""
        MATCH (:Account {username: ${'$'}accountName})-[:OWNS_CALENDAR|MEMBER_OF]->(sc:SharedCalendar {calendarId: ${'$'}calendarId})
        WHERE sc.deletedAt IS NULL
        RETURN count(sc)
    """)
    fun countAccessibleByAccount(calendarId: String, accountName: String): Long

    @Query(
        """
        MATCH (sc:SharedCalendar {calendarId: ${'$'}calendarId})
        OPTIONAL MATCH (a:Appointment)-[:BELONGS_TO_SHARED_CAL]->(sc)
        OPTIONAL MATCH (a)-[:HAS_ATTENDEE]->(att:Attendee)
        OPTIONAL MATCH (a)-[:HAS_REMINDER]->(rem:Reminder)
        OPTIONAL MATCH (a)-[:HAS_MESSAGE]->(msg:Message)
        WITH sc, collect(DISTINCT a) AS appointments, collect(DISTINCT att) AS attendees, collect(DISTINCT rem) AS reminders, collect(DISTINCT msg) AS messages
        OPTIONAL MATCH (b:Booking)-[:BELONGS_TO_SHARED_CAL]->(sc)
        OPTIONAL MATCH (b)-[:HAS_MESSAGE]->(bm:BookingMessage)
        WITH sc, appointments, attendees, reminders, messages, collect(DISTINCT b) AS bookings, collect(DISTINCT bm) AS bookingMessages
        FOREACH (message IN messages | DETACH DELETE message)
        FOREACH (reminder IN reminders | DETACH DELETE reminder)
        FOREACH (attendee IN attendees | DETACH DELETE attendee)
        FOREACH (appointment IN appointments | DETACH DELETE appointment)
        FOREACH (bookingMessage IN bookingMessages | DETACH DELETE bookingMessage)
        FOREACH (booking IN bookings | DETACH DELETE booking)
        DETACH DELETE sc
        """
    )
    fun deleteCalendarGraph(calendarId: String)
}

