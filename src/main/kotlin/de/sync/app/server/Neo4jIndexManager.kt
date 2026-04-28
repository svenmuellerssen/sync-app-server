package de.sync.app.server

import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Creates Neo4j indexes and uniqueness constraints at startup — idempotent via IF NOT EXISTS.
 *
 * Spring Data Neo4j (SDN 7) does not support index creation through field annotations.
 * These indexes are required for acceptable query performance at even modest data sizes:
 * every findBy* repository method and every Cypher MATCH clause that filters on these
 * fields would otherwise cause a full label scan.
 *
 * Indexes created:
 *   Contact:        versionId (unique per version), syncId (index, shared across history), lookupKey, accountName
 *   Appointment:    syncId, accountName, calendarId
 *   CalendarNode:   calendarId (unique), accountName
 *   SharedCalendar: calendarId (unique)
 *   Account:        username (unique)
 *   Booking:        bookingId (unique)
 */
@Component
class Neo4jIndexManager(private val driver: Driver) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(Neo4jIndexManager::class.java)

    override fun run(args: ApplicationArguments) {
        val statements = listOf(
            // Contact
            "CREATE CONSTRAINT contact_versionId_unique IF NOT EXISTS FOR (n:Contact) REQUIRE n.versionId IS UNIQUE",
            "CREATE INDEX contact_syncId IF NOT EXISTS FOR (n:Contact) ON (n.syncId)",
            "CREATE INDEX contact_lookupKey IF NOT EXISTS FOR (n:Contact) ON (n.lookupKey)",
            "CREATE INDEX contact_accountName IF NOT EXISTS FOR (n:Contact) ON (n.accountName)",
            // Appointment
            "CREATE INDEX appointment_syncId IF NOT EXISTS FOR (n:Appointment) ON (n.syncId)",
            "CREATE INDEX appointment_accountName IF NOT EXISTS FOR (n:Appointment) ON (n.accountName)",
            "CREATE INDEX appointment_calendarId IF NOT EXISTS FOR (n:Appointment) ON (n.calendarId)",
            // CalendarNode
            "CREATE CONSTRAINT calendarNode_calendarId_unique IF NOT EXISTS FOR (n:CalendarNode) REQUIRE n.calendarId IS UNIQUE",
            "CREATE INDEX calendarNode_accountName IF NOT EXISTS FOR (n:CalendarNode) ON (n.accountName)",
            // SharedCalendar
            "CREATE CONSTRAINT sharedCalendar_calendarId_unique IF NOT EXISTS FOR (n:SharedCalendar) REQUIRE n.calendarId IS UNIQUE",
            // Account
            "CREATE CONSTRAINT account_username_unique IF NOT EXISTS FOR (n:Account) REQUIRE n.username IS UNIQUE",
            // Booking
            "CREATE CONSTRAINT booking_bookingId_unique IF NOT EXISTS FOR (n:Booking) REQUIRE n.bookingId IS UNIQUE",
        )

        driver.session().use { session ->
            var created = 0
            for (stmt in statements) {
                try {
                    session.run(stmt).consume()
                    created++
                } catch (e: Exception) {
                    log.warn("Index/constraint statement failed (non-fatal): {} — {}", stmt, e.message)
                }
            }
            log.info("Neo4j index setup complete — {} statements executed", created)
        }
    }
}
