package de.sync.app.server

import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

/**
 * Runs schema migrations on startup, before the app is ready to serve HTTP traffic.
 *
 * Migration 1: Appointment versioning (added 2026-04)
 * - Backfills versionId, versionCreatedAt, contentHash on legacy Appointment nodes.
 *
 * Migration 2: CalendarNode + HAS_APPOINTMENT (added 2026-05, redesign)
 * - For each Appointment without an incoming HAS_APPOINTMENT edge, creates a CalendarNode
 *   (find-or-create by calendarName + accountName + calendarType) and adds the edge.
 * - Sets calendarId on the Appointment node (denormalized copy).
 * - Handles SharedCalendar appointments separately (no CalendarNode, edge from SharedCalendar).
 *
 * Migration 3: SharedCalendar OWNS_CALENDAR (added 2026-05, redesign)
 * - Migrates legacy HAS_MEMBER relationships to OWNS_CALENDAR (creator) or keeps MEMBER_OF.
 *   In practice: if a SharedCalendar was created with the new code, OWNS_CALENDAR already exists.
 *   Only legacy nodes that still have HAS_MEMBER without OWNS_CALENDAR need patching.
 */
@Component
class DataMigrationService(private val driver: Driver) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(DataMigrationService::class.java)

    override fun run(vararg args: String?) {
        migrateAppointmentVersioning()
        migrateCalendarNodesAndHasAppointment()
        migrateSharedCalendarOwnsCalendar()
    }

    private fun migrateAppointmentVersioning() {
        driver.session().use { session ->
            val result = session.run("""
                MATCH (a:Appointment)
                WHERE a.versionId IS NULL
                SET a.versionId         = randomUUID(),
                    a.versionCreatedAt  = coalesce(a.lastUpdatedAt, timestamp()),
                    a.contentHash  = ''
                RETURN count(a) AS migrated
            """.trimIndent())
            val migrated = result.single()["migrated"].asLong()
            if (migrated > 0) {
                log.info("Migration 1 (versioning): backfilled {} legacy appointment nodes", migrated)
            }
        }
    }

    /**
     * For every Appointment that has no incoming HAS_APPOINTMENT edge:
     * 1. If it belongs to a SharedCalendar (BELONGS_TO_SHARED_CAL), add HAS_APPOINTMENT from that SharedCalendar.
     * 2. Otherwise, find-or-create a CalendarNode by (calendarName, accountName, calendarType)
     *    and add HAS_APPOINTMENT from CalendarNode → Appointment.
     * Also backfills calendarId on Appointment nodes where it is missing.
     */
    private fun migrateCalendarNodesAndHasAppointment() {
        driver.session().use { session ->
            // Step 1: Wire up SharedCalendar appointments.
            val sharedResult = session.run("""
                MATCH (a:Appointment)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar)
                WHERE NOT (sc)-[:HAS_APPOINTMENT]->(a)
                MERGE (sc)-[:HAS_APPOINTMENT]->(a)
                RETURN count(a) AS migrated
            """.trimIndent())
            val sharedMigrated = sharedResult.single()["migrated"].asLong()
            if (sharedMigrated > 0) {
                log.info("Migration 2a (HAS_APPOINTMENT from SharedCalendar): wired {} appointments", sharedMigrated)
            }

            // Step 2: For personal appointments (no SharedCalendar), find-or-create CalendarNode.
            // calendarName is stored on the Appointment; calendarType defaults to LOCAL.
            val personalResult = session.run("""
                MATCH (a:Appointment)
                WHERE NOT ()-[:HAS_APPOINTMENT]->(a)
                  AND NOT (a)-[:BELONGS_TO_SHARED_CAL]->()
                WITH a,
                     coalesce(a.calendarName, 'Kalender') AS calName,
                     coalesce(a.accountName,  'unknown')  AS accName,
                     coalesce(a.calendarType, 'LOCAL')    AS calType
                MERGE (cal:CalendarNode {name: calName, accountName: accName, calendarType: calType})
                  ON CREATE SET cal.calendarId = randomUUID(),
                                cal.color      = null,
                                cal.deletedAt  = null
                MERGE (cal)-[:HAS_APPOINTMENT]->(a)
                SET a.calendarId = cal.calendarId
                RETURN count(a) AS migrated
            """.trimIndent())
            val personalMigrated = personalResult.single()["migrated"].asLong()
            if (personalMigrated > 0) {
                log.info("Migration 2b (CalendarNode + HAS_APPOINTMENT): wired {} personal appointments", personalMigrated)
            }

            // Step 3: Backfill calendarId on Appointment nodes that are already wired but missing calendarId.
            val backfillResult = session.run("""
                MATCH (cal:CalendarNode)-[:HAS_APPOINTMENT]->(a:Appointment)
                WHERE a.calendarId IS NULL
                SET a.calendarId = cal.calendarId
                RETURN count(a) AS backfilled
            """.trimIndent())
            val backfilled = backfillResult.single()["backfilled"].asLong()
            if (backfilled > 0) {
                log.info("Migration 2c (backfill calendarId): set calendarId on {} appointment nodes", backfilled)
            }
        }
    }

    /**
     * Migrates legacy HAS_MEMBER relationships to the new model.
     * SharedCalendar nodes created with old code may have HAS_MEMBER edges without OWNS_CALENDAR.
     * We promote the createdBy account to OWNS_CALENDAR and convert remaining HAS_MEMBER to MEMBER_OF.
     */
    private fun migrateSharedCalendarOwnsCalendar() {
        driver.session().use { session ->
            // Promote createdBy to OWNS_CALENDAR where it's missing.
            val ownerResult = session.run("""
                MATCH (sc:SharedCalendar)
                WHERE NOT ()-[:OWNS_CALENDAR]->(sc) AND sc.createdBy IS NOT NULL
                MATCH (acc:Account {username: sc.createdBy})
                MERGE (acc)-[:OWNS_CALENDAR]->(sc)
                RETURN count(sc) AS migrated
            """.trimIndent())
            val ownerMigrated = ownerResult.single()["migrated"].asLong()
            if (ownerMigrated > 0) {
                log.info("Migration 3a (OWNS_CALENDAR): promoted {} shared calendar owners", ownerMigrated)
            }

            // Convert legacy HAS_MEMBER to MEMBER_OF (for non-owners).
            val memberResult = session.run("""
                MATCH (acc:Account)-[r:HAS_MEMBER]->(sc:SharedCalendar)
                WHERE acc.username <> sc.createdBy
                MERGE (acc)-[:MEMBER_OF]->(sc)
                DELETE r
                RETURN count(r) AS migrated
            """.trimIndent())
            val memberMigrated = memberResult.single()["migrated"].asLong()
            if (memberMigrated > 0) {
                log.info("Migration 3b (MEMBER_OF): converted {} legacy HAS_MEMBER edges", memberMigrated)
            }

            // Remove remaining HAS_MEMBER from owners (they already have OWNS_CALENDAR now).
            val cleanupResult = session.run("""
                MATCH (acc:Account)-[r:HAS_MEMBER]->(sc:SharedCalendar)
                WHERE acc.username = sc.createdBy
                DELETE r
                RETURN count(r) AS removed
            """.trimIndent())
            val removed = cleanupResult.single()["removed"].asLong()
            if (removed > 0) {
                log.info("Migration 3c (cleanup): removed {} redundant owner HAS_MEMBER edges", removed)
            }
        }
    }
}
