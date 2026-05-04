package de.sync.app.server

import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.CalendarRepository
import de.sync.app.server.graph.ContactNode
import de.sync.app.server.graph.ContactRepository
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
    @Autowired private lateinit var contactRepository: ContactRepository
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
    // AS11: processBatch darf Orphan-Archivierung NICHT selbst durchführen
    //       (Regression für BUG-1 — Archivierung obliegt dem SyncController)
    // -------------------------------------------------------------------------

    @Test
    fun `AS11 processBatch does not archive appointments absent from the batch`() {
        appointmentService.processBatch(
            listOf(dto(syncId = "s1"), dto(syncId = "s2", lastUpdatedAt = 300)),
            TEST_ACCOUNT,
        )

        // Second batch only contains s1 — s2 is absent, but processBatch must NOT archive it.
        // Archival is the responsibility of SyncController.buildAppointmentManifest, not processBatch.
        // BUG-1: archiveOrphanedPersonalAppointments(knownSyncIds=["s1"]) in processBatch archives s2 → RED
        appointmentService.processBatch(listOf(dto(syncId = "s1")), TEST_ACCOUNT)

        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active.map { it.syncId })
            .describedAs("s2 must remain active — processBatch must not archive orphans")
            .containsExactlyInAnyOrder("s1", "s2")
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
    // AS14: BUG-1 — Delta-Upload darf pre-existing Appointments NICHT archivieren
    // -------------------------------------------------------------------------

    @Test
    fun `AS14 BUG-1 delta upload must not archive pre-existing appointments`() {
        // Arrange: zwei Termine bereits auf dem Server gespeichert
        appointmentService.processBatch(listOf(dto(syncId = "s1"), dto(syncId = "s2")), TEST_ACCOUNT)

        // Act: Delta-Upload — nur neuer Termin s3 (simuliert manifest-basierten Incremental-Sync:
        // die App schickt nur toUpload-Einträge, nicht die gesamte Phone-Liste)
        appointmentService.processBatch(listOf(dto(syncId = "s3")), TEST_ACCOUNT)

        // Assert: alle drei Termine müssen aktiv bleiben — s1 und s2 dürfen nicht archiviert werden
        // BUG-1: processBatch() ruft archiveOrphanedPersonalAppointments(knownSyncIds=["s3"]) auf
        // → s1 und s2 werden fälschlicherweise soft-archiviert → Test schlägt fehl bis Bug gefixt
        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active.map { it.syncId }).containsExactlyInAnyOrder("s1", "s2", "s3")
    }

    // -------------------------------------------------------------------------
    // CC4: BUG-2 — findByLookupKey() ohne accountName-Filter → Cross-Account-Leakage
    // -------------------------------------------------------------------------

    @Test
    fun `CC4 BUG-2 attendee contact lookup must not cross account boundaries`() {
        // Arrange: Kontakt von "alice" mit lookupKey "cc4-lk" — TEST_ACCOUNT ("testuser") hat diesen
        // lookupKey NICHT
        contactRepository.save(
            ContactNode(
                syncId = "cc4-contact",
                lookupKey = "cc4-lk",
                accountName = "alice",
                lastUpdatedAt = 100L,
                createdAt = 100L,
                displayName = "Alice Contact",
            )
        )

        // Act: TEST_ACCOUNT lädt Termin hoch mit Attendee, dessen contactLookupKey zu alice gehört
        val attendeeDto = AttendeeDto(name = "Alice", contactLookupKey = "cc4-lk")
        val apptDto = AppointmentDtoRequest(
            syncId = "cc4-apt",
            title = "Meeting",
            dtStart = 1_000_000L,
            dtEnd = 1_003_600L,
            allDay = false,
            timezone = "Europe/Berlin",
            calendarName = "TestKal",
            lastUpdatedAt = 200L,
            attendees = listOf(attendeeDto),
        )
        appointmentService.processBatch(listOf(apptDto), TEST_ACCOUNT)

        // Assert: der Attendee-Node darf KEINE IS_CONTACT-Kante zu Alice's Kontakt haben —
        // TEST_ACCOUNT hat keinen eigenen Kontakt mit diesem lookupKey
        // BUG-2: findByLookupKey("cc4-lk") gibt Alices Kontakt zurück (kein accountName-Filter)
        // → IS_CONTACT-Kante wird trotzdem gesetzt → Test schlägt fehl bis Bug gefixt
        val linkedAccountName = driver.session().use { session ->
            val result = session.run(
                """
                MATCH (:Appointment {syncId: ${'$'}syncId})-[:HAS_ATTENDEE]->(:Attendee)-[:IS_CONTACT]->(c:Contact)
                RETURN c.accountName AS acct
                """.trimIndent(),
                mapOf("syncId" to "cc4-apt"),
            )
            if (result.hasNext()) result.single()["acct"].asString() else null
        }
        assertThat(linkedAccountName)
            .describedAs("Attendee must not be linked to a contact from a different account")
            .isNull()
    }

    // -------------------------------------------------------------------------
    // AS16: BUG-2 — Doppelte HAS_APPOINTMENT-Kanten dürfen findCurrentOrArchivedBySyncId
    //        nicht zum Absturz bringen und processBatch nicht zurückrollen lassen
    // -------------------------------------------------------------------------

    @Test
    fun `AS16 BUG-2 duplicate HAS_APPOINTMENT edges do not crash findCurrentOrArchivedBySyncId`() {
        // Arrange: directly inject two AppointmentNodes with the same syncId and two HAS_APPOINTMENT edges.
        // This simulates corrupted state caused by BUG-1 cycles (each batch re-uploaded the same node).
        driver.session().use { session ->
            session.run(
                """
                CREATE (cal:CalendarNode {calendarId: 'cal-bug2', accountName: ${'$'}acc,
                    name: 'Bug2Cal', calendarType: 'LOCAL'})
                CREATE (a1:Appointment {syncId: ${'$'}sid, accountName: ${'$'}acc, title: 'V1',
                    versionId: randomUUID(), versionCreatedAt: 100, createdAt: 100, lastUpdatedAt: 100,
                    dtStart: 0, dtEnd: 0, allDay: false, timezone: 'UTC',
                    contentHash: 'h1', calendarId: 'cal-bug2'})
                CREATE (a2:Appointment {syncId: ${'$'}sid, accountName: ${'$'}acc, title: 'V2',
                    versionId: randomUUID(), versionCreatedAt: 200, createdAt: 200, lastUpdatedAt: 200,
                    dtStart: 0, dtEnd: 0, allDay: false, timezone: 'UTC',
                    contentHash: 'h2', calendarId: 'cal-bug2'})
                CREATE (cal)-[:HAS_APPOINTMENT]->(a1)
                CREATE (cal)-[:HAS_APPOINTMENT]->(a2)
                """.trimIndent(),
                mapOf("acc" to TEST_ACCOUNT, "sid" to "s-bug2"),
            )
        }

        // Act — must not throw IncorrectResultSizeDataAccessException.
        // BUG-2: RETURN a without LIMIT returns both nodes; SDN6 .single() throws → RED until fixed.
        val found = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "s-bug2")
        assertThat(found).isNotNull()

        // processBatch must not cause UnexpectedRollbackException on this syncId.
        val batchResult = appointmentService.processBatch(
            listOf(dto(syncId = "s-bug2", lastUpdatedAt = 300)),
            TEST_ACCOUNT,
        )
        assertThat(batchResult.stored + batchResult.skipped)
            .describedAs("batch must not roll back — exactly one entry stored or skipped")
            .isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // AS15: Archived appointment with content change is re-created as new version
    // -------------------------------------------------------------------------

    @Test
    fun `AS15 archived appointment with content change is re-created as new version`() {
        // Upload V1
        appointmentService.processBatch(
            listOf(dto(syncId = "s1", title = "V1", lastUpdatedAt = 100)),
            TEST_ACCOUNT,
        )

        // Archive V1 — simulate the phone no longer having this appointment
        appointmentRepository.archiveOrphanedPersonalAppointments(TEST_ACCOUNT, emptyList(), NOW)

        val archivedNodes = appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1")
        assertThat(archivedNodes).hasSize(1)
        assertThat(archivedNodes.single().deletedAt).isNotNull()

        // Upload same syncId with a different title (content change) and higher lastUpdatedAt
        val result = appointmentService.processBatch(
            listOf(dto(syncId = "s1", title = "V2-changed", lastUpdatedAt = 200)),
            TEST_ACCOUNT,
        )

        assertThat(result.stored).isEqualTo(1)

        // Two nodes must exist for this syncId: the archived V1 + the new V2
        val allNodes = appointmentRepository.findAllBySyncIdOrderByVersionCreatedAtDesc("s1")
        assertThat(allNodes).hasSize(2)

        // Only V2 is active (deletedAt = null) and has the updated title
        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().title).isEqualTo("V2-changed")
        assertThat(active.single().deletedAt).isNull()
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
