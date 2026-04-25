package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface AppointmentRepository : Neo4jRepository<AppointmentNode, Long> {

    // --- Current version queries (via HAS_APPOINTMENT graph traversal) ---

    /**
     * Returns all current (active) personal appointments for an account.
     * "Current" = reachable via CalendarNode-[:HAS_APPOINTMENT]->Appointment.
     * Matches all calendarTypes (LOCAL, GOOGLE) — SharedCalendar appointments use SharedCalendarNode,
     * not CalendarNode, so they are naturally excluded here (G5 fix preserved).
     */
    @Query("MATCH (:CalendarNode {accountName: \$accountName})-[:HAS_APPOINTMENT]->(a:Appointment) WHERE a.deletedAt IS NULL RETURN a")
    fun findAllCurrentByAccountName(accountName: String): List<AppointmentNode>

    /** Counts all current appointments for an account: personal (CalendarNode) + own shared-calendar entries. */
    @Query("""
        MATCH (n)-[:HAS_APPOINTMENT]->(a:Appointment)
        WHERE (
            (n:CalendarNode AND n.accountName = ${'$'}accountName)
            OR (n:SharedCalendar AND n.deletedAt IS NULL AND a.accountName = ${'$'}accountName)
        ) AND a.deletedAt IS NULL
        RETURN count(a)
    """)
    fun countAllCurrentByAccountName(accountName: String): Long

    @Query("MATCH (:CalendarNode {accountName: \$accountName})-[:HAS_APPOINTMENT]->(a:Appointment) WHERE a.deletedAt IS NULL RETURN count(a)")
    fun countCurrentByAccountName(accountName: String): Long

    /** All current appointments in shared calendars that were uploaded by this account. */
    @Query("MATCH (sc:SharedCalendar)-[:HAS_APPOINTMENT]->(a:Appointment {accountName: \$accountName}) WHERE sc.deletedAt IS NULL AND a.deletedAt IS NULL RETURN a")
    fun findAllCurrentSharedByAccountName(accountName: String): List<AppointmentNode>

    /**
     * Returns the current (active, not soft-archived) version of an appointment.
     * Use [findCurrentOrArchivedBySyncId] in processSingle to also find archived nodes for un-delete.
     */
    @Query("MATCH ()-[:HAS_APPOINTMENT]->(a:Appointment {syncId: \$syncId}) WHERE a.deletedAt IS NULL RETURN a")
    fun findCurrentBySyncId(syncId: String): AppointmentNode?

    /**
     * Returns the current version of an appointment regardless of archive state.
     * Used in processSingle so that archived appointments can be un-deleted or continued as a new version.
     */
    @Query("MATCH ()-[:HAS_APPOINTMENT]->(a:Appointment {syncId: \$syncId}) RETURN a")
    fun findCurrentOrArchivedBySyncId(syncId: String): AppointmentNode?

    @Query("""
        MATCH (:CalendarNode {accountName: ${'$'}accountName})-[:HAS_APPOINTMENT]->(a:Appointment)
        WHERE a.dtStart >= ${'$'}from AND a.dtStart <= ${'$'}to AND a.deletedAt IS NULL
        RETURN a
    """)
    fun findAllCurrentByAccountNameAndDtStartBetween(
        accountName: String,
        from: Long,
        to: Long,
    ): List<AppointmentNode>

    // --- Version history ---

    /** All versions of an appointment, newest first (by server-assigned versionCreatedAt). */
    fun findAllBySyncIdOrderByVersionCreatedAtDesc(syncId: String): List<AppointmentNode>

    // --- HAS_APPOINTMENT edge management ---

    /**
     * Removes the HAS_APPOINTMENT edge pointing to this appointment node.
     * Soft-archives the appointment: it remains in the graph but is no longer "current".
     * The PREVIOUS_VERSION chain is fully preserved.
     */
    @Query("MATCH ()-[r:HAS_APPOINTMENT]->(a:Appointment) WHERE id(a) = \$id DELETE r")
    fun removeHasAppointmentEdge(id: Long)

    /**
     * Creates a HAS_APPOINTMENT edge from a SharedCalendarNode to an AppointmentNode.
     * Used when saving a new version of a shared-calendar appointment.
     */
    @Query("""
        MATCH (sc:SharedCalendar {calendarId: ${'$'}calendarId}), (a:Appointment)
        WHERE id(a) = ${'$'}appointmentId
        MERGE (sc)-[:HAS_APPOINTMENT]->(a)
    """)
    fun addHasAppointmentEdgeFromSharedCalendar(calendarId: String, appointmentId: Long)

    /** Creates a PREVIOUS_VERSION relationship from the new node to the old node. */
    @Query("MATCH (n:Appointment) WHERE id(n) = \$newId MATCH (o:Appointment) WHERE id(o) = \$oldId MERGE (n)-[:PREVIOUS_VERSION]->(o)")
    fun linkVersions(newId: Long, oldId: Long)

    /** Deletes all versions of an appointment (latest + history). */
    @Query("MATCH (a:Appointment {syncId: \$syncId}) DETACH DELETE a")
    fun deleteAllVersionsBySyncId(syncId: String)

    /** Clears deletedAt on an archived appointment node (un-archive). */
    @Query("MATCH (a:Appointment) WHERE id(a) = \$id SET a.deletedAt = null")
    fun clearDeletedAt(id: Long)

    /**
     * Soft-archives a single appointment node by setting deletedAt.
     * Used by SyncController (manifest reconciliation) and dedup logic.
     * Keeps the HAS_APPOINTMENT edge intact so history remains traversable from CalendarNode.
     * Use [removeHasAppointmentEdge] for version replacement (not soft-archive).
     */
    @Query("MATCH (a:Appointment) WHERE id(a) = \$id SET a.deletedAt = \$now")
    fun softArchiveById(id: Long, now: Long)

    // --- Cross-calendar queries ---

    /** Returns all current appointments in a shared calendar. */
    @Query("MATCH (:SharedCalendar {calendarId: \$calendarId})-[:HAS_APPOINTMENT]->(a:Appointment) WHERE a.deletedAt IS NULL RETURN a")
    fun findAllCurrentBySharedCalendarId(calendarId: String): List<AppointmentNode>

    /** Returns all current appointments across multiple shared calendars in a single query. */
    @Query("MATCH (sc:SharedCalendar)-[:HAS_APPOINTMENT]->(a:Appointment) WHERE sc.calendarId IN \$calendarIds AND sc.deletedAt IS NULL AND a.deletedAt IS NULL RETURN a")
    fun findAllCurrentBySharedCalendarIds(calendarIds: List<String>): List<AppointmentNode>

    /**
     * Returns all appointments that overlap [from, to) and are visible to the given account on their device.
     * Covers exactly what the phone can display:
     *   1. Personal calendars (CalendarNode, LOCAL + GOOGLE)
     *   2. SharedCalendar appointments from ALL members, but ONLY in calendars where this
     *      account is owner (OWNS_CALENDAR) or member (MEMBER_OF).
     *      → Appointments in calendars the account has no access to are excluded.
     *
     * Used by SlotService to calculate busy intervals for booking slot proposals.
     */
    @Query("""
        MATCH (n)-[:HAS_APPOINTMENT]->(a:Appointment)
        WHERE a.dtStart < ${'$'}to
          AND coalesce(a.dtEnd, a.dtStart + 900000) > ${'$'}from
          AND a.deletedAt IS NULL
          AND (
            (n:CalendarNode AND n.accountName = ${'$'}accountName)
            OR (n:SharedCalendar AND n.deletedAt IS NULL AND a.accountName = ${'$'}accountName)
            OR (n:SharedCalendar AND n.deletedAt IS NULL AND EXISTS {
                MATCH (:Account {username: ${'$'}accountName})-[:OWNS_CALENDAR|MEMBER_OF]->(n)
            })
          )
        RETURN DISTINCT a ORDER BY a.dtStart ASC
    """)
    fun findAllOverlappingRange(accountName: String, from: Long, to: Long): List<AppointmentNode>

    /**
     * Soft-archives personal appointments that are no longer present on the phone.
     *
     * Sets [AppointmentNode.deletedAt] on every current personal appointment whose syncId
     * is NOT in [knownSyncIds]. The HAS_APPOINTMENT edge and all version nodes are preserved —
     * history remains fully traversable from the CalendarNode.
     *
     * Un-archiving happens automatically in processSingle when the phone re-syncs the same syncId.
     *
     * SharedCalendar appointments are excluded by design.
     */
    @Query("""
        MATCH (cal:CalendarNode {accountName: ${'$'}accountName})-[:HAS_APPOINTMENT]->(a:Appointment)
        WHERE NOT a.syncId IN ${'$'}knownSyncIds AND a.deletedAt IS NULL
        SET a.deletedAt = ${'$'}now
    """)
    fun archiveOrphanedPersonalAppointments(accountName: String, knownSyncIds: List<String>, now: Long)
}

