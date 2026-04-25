package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface BookingRepository : Neo4jRepository<BookingNode, Long> {

    /**
     * Returns all active bookings overlapping [from, to) that are visible to the given account.
     * Covers:
     *   1. Own bookings (accountName = this user)
     *   2. Other members' bookings in SharedCalendars where this user is owner or member
     *
     * Explicitly excludes bookings whose linked SharedCalendar has been soft-deleted,
     * as a defence-in-depth guard against orphan bookings that survived a race condition.
     *
     * Used by SlotService (busy intervals) and createBooking/updateBooking (overlap guard).
     */
    @Query("""
        MATCH (b:Booking)
        WHERE b.cancelledAt IS NULL
          AND b.startTime < ${'$'}to
          AND b.endTime > ${'$'}from
          AND NOT EXISTS {
              MATCH (b)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar)
              WHERE sc.deletedAt IS NOT NULL
          }
          AND (
            b.accountName = ${'$'}accountName
            OR EXISTS {
                MATCH (b)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar)
                MATCH (:Account {username: ${'$'}accountName})-[:OWNS_CALENDAR|MEMBER_OF]->(sc)
            }
          )
        RETURN b ORDER BY b.startTime ASC
    """)
    fun findAllOverlappingRange(accountName: String, from: Long, to: Long): List<BookingNode>

    /**
     * Derived query — SDN 6 eager-loads sharedCalendar, invitees, messages, creator.
     * Use this for list/get endpoints where relationship data is needed in the response.
     */
    fun findAllByAccountNameAndCancelledAtIsNullOrderByStartTimeAsc(accountName: String): List<BookingNode>

    /**
     * Returns internal IDs of all active bookings visible to the given account:
     * own bookings + bookings in SharedCalendars where the account is owner or member.
     * Results are ordered by startTime ascending.
     *
     * Use findAllById() after this call to load full entities with all relationships.
     */
    @Query("""
        MATCH (b:Booking)
        WHERE b.cancelledAt IS NULL
          AND NOT EXISTS {
              MATCH (b)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar)
              WHERE sc.deletedAt IS NOT NULL
          }
          AND (
            b.accountName = ${'$'}accountName
            OR EXISTS {
                MATCH (b)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar)
                MATCH (:Account {username: ${'$'}accountName})-[:OWNS_CALENDAR|MEMBER_OF]->(sc)
            }
          )
        RETURN id(b) ORDER BY b.startTime ASC
    """)
    fun findAllVisibleIdsByAccountName(accountName: String): List<Long>

    @Query("MATCH (b:Booking) WHERE id(b) = \$id SET b.cancelledAt = \$cancelledAt")
    fun softDeleteById(id: Long, cancelledAt: Long)

    /**
     * Soft-deletes all active bookings belonging to the given SharedCalendar.
     * Called when the SharedCalendar itself is soft-deleted, to cascade the deletion.
     */
    @Query("""
        MATCH (b:Booking)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar {calendarId: ${'$'}calendarId})
        WHERE b.cancelledAt IS NULL
        SET b.cancelledAt = ${'$'}cancelledAt
    """)
    fun softDeleteAllBySharedCalendarId(calendarId: String, cancelledAt: Long)

    @Query("MATCH (b:Booking) WHERE id(b) = \$bookingId OPTIONAL MATCH (b)-[:HAS_MESSAGE]->(m:BookingMessage) WITH b, collect(DISTINCT m) AS messages FOREACH (message IN messages | DETACH DELETE message) DETACH DELETE b")
    fun deleteBookingGraph(bookingId: Long)

    @Query("MATCH (b:Booking)-[r:HAS_MESSAGE]->(m:BookingMessage) WHERE id(b) = \$bookingId AND id(m) = \$messageId DELETE r, m RETURN count(*)")
    fun deleteMessage(bookingId: Long, messageId: Long): Long

    /**
     * Updates the text and editedAt timestamp of a message.
     * Only the sender may call this — ownership check is enforced in the controller.
     * Returns the updated node, or null if the booking/message combination does not exist.
     */
    @Query("""
        MATCH (b:Booking)-[:HAS_MESSAGE]->(m:BookingMessage)
        WHERE id(b) = ${'$'}bookingId AND id(m) = ${'$'}messageId
        SET m.text = ${'$'}text, m.editedAt = ${'$'}editedAt
        RETURN m
    """)
    fun updateMessage(bookingId: Long, messageId: Long, text: String, editedAt: Long): BookingMessageNode?
}
