package de.sync.app.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration tests for DataMigrationService.
 *
 * DM1: Running all migrations on an empty DB completes without exceptions.
 * DM2: Running migrations twice on a legacy node does not duplicate HAS_APPOINTMENT edges.
 * DM3: Migration 4 (dedup) is idempotent — second run on already-clean data is a no-op.
 */
@SpringBootTest
@Testcontainers
class DataMigrationServiceIntegrationTest {

    @Autowired private lateinit var migrationService: DataMigrationService
    @Autowired private lateinit var neo4jIndexManager: Neo4jIndexManager
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
    }

    // -------------------------------------------------------------------------
    // DM1: Empty DB — no exceptions
    // -------------------------------------------------------------------------

    @Test
    fun `DM1 all migrations on empty database complete without exceptions`() {
        // DataMigrationService already ran at Spring Boot startup.
        // BeforeEach cleared the DB. Running again must be safe (empty DB = 0 migrations).
        migrationService.run()
    }

    // -------------------------------------------------------------------------
    // DM2: Running twice does not duplicate HAS_APPOINTMENT edges
    // -------------------------------------------------------------------------

    @Test
    fun `DM2 running migrations twice does not duplicate HAS_APPOINTMENT edges`() {
        // Create a legacy Appointment without versionId (pre-migration state)
        driver.session().use { session ->
            session.run(
                """
                CREATE (:Appointment {
                    syncId: 'dm2-s1', accountName: 'testuser',
                    title: 'Meeting', dtStart: 1000, dtEnd: 2000,
                    allDay: false, timezone: 'UTC', lastUpdatedAt: 100
                })
                """.trimIndent()
            )
        }

        migrationService.run()

        val edgesAfterFirst = countHasAppointmentEdges("dm2-s1")
        assertThat(edgesAfterFirst).isEqualTo(1)

        migrationService.run()

        val edgesAfterSecond = countHasAppointmentEdges("dm2-s1")
        assertThat(edgesAfterSecond)
            .describedAs("Second migration run must not create additional HAS_APPOINTMENT edges")
            .isEqualTo(edgesAfterFirst)
    }

    // -------------------------------------------------------------------------
    // DM3: Migration 4 dedup is idempotent
    // -------------------------------------------------------------------------

    @Test
    fun `DM3 migration 4 run twice after dedup leaves exactly one active HAS_APPOINTMENT per syncId`() {
        // Setup: simulate BUG-A state — two Appointment nodes with same syncId,
        // each with its own HAS_APPOINTMENT edge from the CalendarNode
        driver.session().use { session ->
            session.run(
                """
                CREATE (cal:CalendarNode {
                    calendarId: 'dm3-cal', name: 'Test',
                    accountName: 'testuser', calendarType: 'LOCAL'
                })
                CREATE (a1:Appointment {
                    syncId: 'dm3-sid', accountName: 'testuser',
                    title: 'T1', versionId: randomUUID(), versionCreatedAt: 100,
                    createdAt: 100, lastUpdatedAt: 100,
                    dtStart: 1000, dtEnd: 2000, allDay: false, timezone: 'UTC',
                    contentHash: 'h1', calendarId: 'dm3-cal'
                })
                CREATE (a2:Appointment {
                    syncId: 'dm3-sid', accountName: 'testuser',
                    title: 'T2', versionId: randomUUID(), versionCreatedAt: 200,
                    createdAt: 200, lastUpdatedAt: 200,
                    dtStart: 1000, dtEnd: 2000, allDay: false, timezone: 'UTC',
                    contentHash: 'h2', calendarId: 'dm3-cal'
                })
                CREATE (cal)-[:HAS_APPOINTMENT]->(a1)
                CREATE (cal)-[:HAS_APPOINTMENT]->(a2)
                """.trimIndent()
            )
        }

        migrationService.run()

        val edgesAfterFirst = countHasAppointmentEdges("dm3-sid")
        assertThat(edgesAfterFirst)
            .describedAs("Migration 4 must deduplicate to exactly 1 active HAS_APPOINTMENT edge")
            .isEqualTo(1)

        migrationService.run()

        val edgesAfterSecond = countHasAppointmentEdges("dm3-sid")
        assertThat(edgesAfterSecond)
            .describedAs("Second run must be idempotent — still exactly 1 edge")
            .isEqualTo(1)
    }

    @Test
    fun `DM4 should remove legacy unique constraint on Contact syncId when present`() {
        driver.session().use { session ->
            session.run("DROP INDEX contact_syncId IF EXISTS").consume()
            session.run(
                "CREATE CONSTRAINT legacy_contact_syncId_unique IF NOT EXISTS FOR (c:Contact) REQUIRE c.syncId IS UNIQUE"
            ).consume()
        }

        assertThat(hasConstraint("legacy_contact_syncId_unique")).isTrue()

        neo4jIndexManager.run(DefaultApplicationArguments(*emptyArray<String>()))

        assertThat(hasConstraint("legacy_contact_syncId_unique")).isFalse()
    }

    @Test
    fun `DM5 should keep contact versionId unique constraint after removing legacy syncId constraint`() {
        driver.session().use { session ->
            session.run("DROP INDEX contact_syncId IF EXISTS").consume()
            session.run(
                "CREATE CONSTRAINT legacy_contact_syncId_unique IF NOT EXISTS FOR (c:Contact) REQUIRE c.syncId IS UNIQUE"
            ).consume()
        }

        neo4jIndexManager.run(DefaultApplicationArguments(*emptyArray<String>()))

        assertThat(hasConstraint("legacy_contact_syncId_unique")).isFalse()
        assertThat(hasConstraint("contact_versionId_unique")).isTrue()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun countHasAppointmentEdges(syncId: String): Long =
        driver.session().use { session ->
            session.run(
                "MATCH ()-[:HAS_APPOINTMENT]->(a:Appointment {syncId: \$sid}) RETURN count(*) AS cnt",
                mapOf("sid" to syncId),
            ).single()["cnt"].asLong()
        }

    private fun hasConstraint(constraintName: String): Boolean =
        driver.session().use { session ->
            val result = session.run(
                """
                SHOW CONSTRAINTS YIELD name
                WHERE name = ${'$'}name
                RETURN count(*) AS cnt
                """.trimIndent(),
                mapOf("name" to constraintName),
            )
            result.single()["cnt"].asLong() > 0
        }

    companion object {
        @Container @JvmStatic
        val neo4j: Neo4jContainer<*> = Neo4jContainer("neo4j:5").withoutAuthentication()

        @Container @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.neo4j.uri") { neo4j.boltUrl }
            registry.add("spring.neo4j.authentication.username") { "neo4j" }
            registry.add("spring.neo4j.authentication.password") { "" }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }
}
