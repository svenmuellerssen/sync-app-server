package de.sync.app.server

import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.CalendarRepository
import de.sync.app.server.graph.SharedCalendarRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class AppointmentServiceIntegrationTest {

    @Autowired private lateinit var appointmentService: AppointmentService
    @Autowired private lateinit var appointmentRepository: AppointmentRepository
    @Autowired private lateinit var calendarRepository: CalendarRepository
    @Autowired private lateinit var sharedCalendarRepository: SharedCalendarRepository
    @Autowired private lateinit var accountRepository: AccountRepository
    @Autowired private lateinit var stringRedisTemplate: StringRedisTemplate
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        stringRedisTemplate.keys("slots:*").takeIf { it.isNotEmpty() }?.let {
            stringRedisTemplate.delete(it)
        }
    }

    // -------------------------------------------------------------------------
    // AS1: Stale-overwrite protection
    // -------------------------------------------------------------------------

    @Test
    fun `AS1 stale upload is skipped — older lastUpdatedAt does not overwrite newer node`() {
        appointmentService.processBatch(listOf(dto(syncId = "s1", title = "V1", lastUpdatedAt = 200)), TEST_ACCOUNT)

        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", title = "V2-stale", lastUpdatedAt = 100)),
            TEST_ACCOUNT,
        )

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.stored).isEqualTo(0)
        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().title).isEqualTo("V1")
    }

    // -------------------------------------------------------------------------
    // AS2: Content-hash dedup — unchanged content is skipped
    // -------------------------------------------------------------------------

    @Test
    fun `AS2 uploading same appointment twice skips the second upload`() {
        appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        val result = appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        assertThat(result.skipped).isEqualTo(1)
        assertThat(appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1")).hasSize(1)
    }

    // -------------------------------------------------------------------------
    // AS3: Archived appointment with same content is un-archived — no new node
    // -------------------------------------------------------------------------

    @Test
    fun `AS3 archived appointment with unchanged content is re-activated without creating a new node`() {
        appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        // Simulate orphan-archival (phone removed appointment from sync)
        appointmentRepository.archiveOrphanedPersonalAppointments(TEST_ACCOUNT, emptyList(), NOW)

        val archived = appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1").single()
        assertThat(archived.deletedAt).isNotNull()

        // Upload same content again
        val result = appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        assertThat(result.stored).isEqualTo(1)
        // Still only one node — no duplicate
        assertThat(appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1")).hasSize(1)
        // Node is active again
        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().deletedAt).isNull()
    }

    // -------------------------------------------------------------------------
    // AS4: New appointment — single node, no history edge
    // -------------------------------------------------------------------------

    @Test
    fun `AS4 new appointment is stored with no previous version edge`() {
        val result = appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        assertThat(result.stored).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)).hasSize(1)
        assertThat(countPreviousVersionEdges("s1")).isEqualTo(0L)
    }

    // -------------------------------------------------------------------------
    // AS5: Content change creates new version node with history chain
    // -------------------------------------------------------------------------

    @Test
    fun `AS5 updated appointment creates new version node and PREVIOUS_VERSION edge`() {
        appointmentService.processBatch(listOf(dto(syncId = "s1", title = "V1", lastUpdatedAt = 100)), TEST_ACCOUNT)
        appointmentService.processBatch(listOf(dto(syncId = "s1", title = "V2", lastUpdatedAt = 200)), TEST_ACCOUNT)

        // Two nodes for the same syncId
        assertThat(appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1")).hasSize(2)

        // Only V2 is active (has HAS_APPOINTMENT edge)
        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().title).isEqualTo("V2")

        // History edge exists
        assertThat(countPreviousVersionEdges("s1")).isEqualTo(1L)
    }

    // -------------------------------------------------------------------------
    // AS6: SharedCalendar — owner can upload appointments
    // -------------------------------------------------------------------------

    @Test
    fun `AS6 owner can upload appointments to a shared calendar`() {
        val calId = "shared-cal-1"
        createSharedCalendarWithOwner(calId, TEST_ACCOUNT)

        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", sharedCalendarId = calId)),
            TEST_ACCOUNT,
        )

        assertThat(result.stored).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // AS7: SharedCalendar — non-member is rejected
    // -------------------------------------------------------------------------

    @Test
    fun `AS7 upload to shared calendar where account is not member is skipped`() {
        val calId = "shared-cal-1"
        createSharedCalendarWithOwner(calId, "other-user")

        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", sharedCalendarId = calId)),
            TEST_ACCOUNT,
        )

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.stored).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // AS8: serverCalendarId set, CalendarNode not found → skipped
    // -------------------------------------------------------------------------

    @Test
    fun `AS8 upload with unknown serverCalendarId is skipped`() {
        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", serverCalendarId = "does-not-exist")),
            TEST_ACCOUNT,
        )

        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.stored).isEqualTo(0)
    }

    // -------------------------------------------------------------------------
    // AS9: Bootstrap sync — new CalendarNode is created and returned
    // -------------------------------------------------------------------------

    @Test
    fun `AS9 first sync without serverCalendarId creates a new CalendarNode`() {
        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", calendarName = "Mein Kalender")),
            TEST_ACCOUNT,
        )

        assertThat(result.stored).isEqualTo(1)
        assertThat(result.newCalendars).hasSize(1)
        assertThat(result.newCalendars.single().name).isEqualTo("Mein Kalender")

        val cals = calendarRepository.findAllActiveByAccountName(TEST_ACCOUNT)
        assertThat(cals).hasSize(1)
    }

    // -------------------------------------------------------------------------
    // AS10: Bootstrap sync — existing CalendarNode is reused, not duplicated
    // -------------------------------------------------------------------------

    @Test
    fun `AS10 second bootstrap upload reuses existing CalendarNode`() {
        appointmentService.processBatch(
            listOf(dto(syncId = "s1", calendarName = "Mein Kalender")),
            TEST_ACCOUNT,
        )

        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s2", calendarName = "Mein Kalender", lastUpdatedAt = 500)),
            TEST_ACCOUNT,
        )

        assertThat(result.newCalendars).isEmpty()
        assertThat(calendarRepository.findAllActiveByAccountName(TEST_ACCOUNT)).hasSize(1)
    }

    // -------------------------------------------------------------------------
    // AS11: Orphan archivization — appointment absent from batch is archived
    // -------------------------------------------------------------------------

    @Test
    fun `AS11 appointment absent from batch is soft-archived after upload`() {
        appointmentService.processBatch(
            listOf(dto(syncId = "s1"), dto(syncId = "s2", lastUpdatedAt = 300)),
            TEST_ACCOUNT,
        )

        // Second batch only contains s1 — s2 is gone from the phone
        appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().syncId).isEqualTo("s1")

        val all = appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s2")
        assertThat(all.single().deletedAt).isNotNull()
    }

    // -------------------------------------------------------------------------
    // AS12: Slot-cache invalidation — account cache is cleared after batch
    // -------------------------------------------------------------------------

    @Test
    fun `AS12 slot cache for account is cleared after batch`() {
        val cacheKey = "slots:$TEST_ACCOUNT:Europe/Berlin:2026-05-01"
        stringRedisTemplate.opsForValue().set(cacheKey, "[]")
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue()

        appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        assertThat(stringRedisTemplate.keys("slots:$TEST_ACCOUNT:*")).isEmpty()
    }

    // -------------------------------------------------------------------------
    // AS13: SharedCal member cache invalidation
    // -------------------------------------------------------------------------

    @Test
    fun `AS13 slot cache of shared calendar members is cleared after batch`() {
        val calId = "shared-cal-1"
        createSharedCalendarWithOwner(calId, TEST_ACCOUNT)
        addMemberToSharedCalendar(calId, "bob")

        val bobCacheKey = "slots:bob:Europe/Berlin:2026-05-01"
        stringRedisTemplate.opsForValue().set(bobCacheKey, "[]")

        appointmentService.processBatch(
            listOf(dto(syncId = "s1", sharedCalendarId = calId)),
            TEST_ACCOUNT,
        )

        assertThat(stringRedisTemplate.keys("slots:bob:*")).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun dto(
        syncId: String,
        title: String = "Test Appointment",
        calendarName: String? = "TestKal",
        lastUpdatedAt: Long = 200L,
        serverCalendarId: String? = null,
        sharedCalendarId: String? = null,
    ) = AppointmentDtoRequest(
        syncId = syncId,
        title = title,
        dtStart = 1_000_000L,
        dtEnd = 1_003_600L,
        allDay = false,
        timezone = "Europe/Berlin",
        calendarName = calendarName,
        lastUpdatedAt = lastUpdatedAt,
        serverCalendarId = serverCalendarId,
        sharedCalendarId = sharedCalendarId,
    )

    private fun createSharedCalendarWithOwner(calendarId: String, ownerUsername: String) {
        driver.session().use { session ->
            session.run(
                """
                MERGE (a:Account {username: ${'$'}owner, passwordHash: 'x', createdAt: 0})
                CREATE (sc:SharedCalendar {calendarId: ${'$'}calId, name: 'Test Cal', createdBy: ${'$'}owner, createdAt: 0})
                CREATE (a)-[:OWNS_CALENDAR]->(sc)
                """.trimIndent(),
                mapOf("owner" to ownerUsername, "calId" to calendarId),
            )
        }
    }

    private fun addMemberToSharedCalendar(calendarId: String, memberUsername: String) {
        driver.session().use { session ->
            session.run(
                """
                MERGE (a:Account {username: ${'$'}member, passwordHash: 'x', createdAt: 0})
                WITH a
                MATCH (sc:SharedCalendar {calendarId: ${'$'}calId})
                CREATE (a)-[:MEMBER_OF]->(sc)
                """.trimIndent(),
                mapOf("member" to memberUsername, "calId" to calendarId),
            )
        }
    }

    private fun countPreviousVersionEdges(syncId: String): Long =
        driver.session().use { session ->
            session.run(
                "MATCH (:Appointment {syncId: \$syncId})-[:PREVIOUS_VERSION]->(:Appointment {syncId: \$syncId}) RETURN count(*) AS c",
                mapOf("syncId" to syncId),
            ).single()["c"].asLong()
        }

    companion object {
        const val TEST_ACCOUNT = "testuser"
        private val NOW = System.currentTimeMillis()

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
