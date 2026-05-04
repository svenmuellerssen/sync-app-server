package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.dto.ManifestRequest
import de.sync.app.server.dto.SyncEntry
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.ContactNode
import de.sync.app.server.graph.ContactRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SyncControllerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appointmentService: AppointmentService
    @Autowired private lateinit var appointmentRepository: AppointmentRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var sessionRepository: SessionRepository
    @Autowired private lateinit var stringRedisTemplate: StringRedisTemplate
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        stringRedisTemplate.keys("slots:*").takeIf { it.isNotEmpty() }?.let { stringRedisTemplate.delete(it) }
        sessionRepository.save(SessionEntity(token = TEST_TOKEN, accountName = TEST_ACCOUNT))
    }

    // -------------------------------------------------------------------------
    // SC1: Termin auf Phone, nicht auf Server → syncId landet in toUpload
    // -------------------------------------------------------------------------

    @Test
    fun `SC1 appointment on phone but not on server is listed in toUpload`() {
        val body = manifest(appointments = listOf(SyncEntry("s1", 100L)), type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toUpload[0]").value("s1"))
    }

    // -------------------------------------------------------------------------
    // SC2: Termin auf Server, nicht auf Phone → Merge-Modell: toDownload, KEIN Archivieren
    // -------------------------------------------------------------------------

    @Test
    fun `SC2 appointment on server absent from phone manifest is returned in toDownload not archived`() {
        appointmentService.processBatch(listOf(apt("s1")), TEST_ACCOUNT)

        // Phone sends s2 but NOT s1 — merge model: s1 must be downloaded, never archived
        val body = manifest(appointments = listOf(SyncEntry("s2", 100L)), type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDownload[0].syncId").value("s1"))

        val s1 = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "s1")
        assertThat(s1?.deletedAt).describedAs("s1 must NOT be soft-archived — merge model never archives missing appointments").isNull()
    }

    // -------------------------------------------------------------------------
    // SC3: Leere Liste + confirmedEmpty=false → G1-Guard: kein Archivieren
    // -------------------------------------------------------------------------

    @Test
    fun `SC3 G1 guard — empty phone list without confirmedEmpty does not archive server appointments`() {
        appointmentService.processBatch(listOf(apt("s1")), TEST_ACCOUNT)

        val body = manifest(appointments = emptyList(), confirmedEmpty = false, type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk)

        val s1 = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "s1")
        assertThat(s1?.deletedAt).describedAs("G1 guard must protect s1 from archiving").isNull()
    }

    // -------------------------------------------------------------------------
    // SC4: Leere Liste + confirmedEmpty=true → Merge-Modell: toDownload, KEIN Archivieren
    // -------------------------------------------------------------------------

    @Test
    fun `SC4 empty phone list with confirmedEmpty true returns personal appointments in toDownload not archived`() {
        appointmentService.processBatch(listOf(apt("s1")), TEST_ACCOUNT)

        val body = manifest(appointments = emptyList(), confirmedEmpty = true, type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDownload[0].syncId").value("s1"))

        val s1 = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "s1")
        assertThat(s1?.deletedAt).describedAs("s1 must NOT be archived even with confirmedEmpty=true — merge model ignores confirmedEmpty").isNull()
    }

    // -------------------------------------------------------------------------
    // SC5: Duplikat-Termine (gleicher DedupKey) → Ältester bleibt, anderer archiviert
    // -------------------------------------------------------------------------

    @Test
    fun `SC5 duplicate appointments with same DedupKey — oldest survives, newer is archived`() {
        val title = "SC5-Duplikat"
        val dtStart = 2_000_000L
        val dtEnd   = 2_003_600L

        appointmentService.processBatch(listOf(apt("s5a", title = title, dtStart = dtStart, dtEnd = dtEnd)), TEST_ACCOUNT)
        Thread.sleep(20) // ensure distinct createdAt — dedup keeps minByOrNull { createdAt }
        appointmentService.processBatch(listOf(apt("s5b", title = title, dtStart = dtStart, dtEnd = dtEnd)), TEST_ACCOUNT)

        // Phone sends both syncIds — dedup fires on the server side
        val body = manifest(
            appointments = listOf(SyncEntry("s5a", 200L), SyncEntry("s5b", 200L)),
            type = "appointments",
        )

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk)

        val active = appointmentRepository.findAllCurrentByAccountName(TEST_ACCOUNT)
        assertThat(active.map { it.syncId })
            .describedAs("only oldest appointment must remain active")
            .containsExactly("s5a")

        val s5b = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "s5b")
        assertThat(s5b?.deletedAt).describedAs("s5b must be archived as duplicate").isNotNull()
    }

    // -------------------------------------------------------------------------
    // SC6: SharedCal-Termin eines anderen Members → in toDownload
    // -------------------------------------------------------------------------

    @Test
    fun `SC6 shared calendar appointment from another member is listed in toDownload`() {
        val calId = "sc-cal-6"
        createSharedCalendar(calId, ownerUsername = "alice")
        addMember(calId, memberUsername = TEST_ACCOUNT)

        // Alice uploads an appointment to the shared calendar (direct service call, no auth needed)
        appointmentService.processBatch(listOf(apt("s6-alice", sharedCalendarId = calId)), "alice")

        // TEST_ACCOUNT calls manifest — empty local list, confirmedEmpty=true (no personal appointments)
        val body = manifest(appointments = emptyList(), confirmedEmpty = true, type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDownload[0].syncId").value("s6-alice"))
    }

    // -------------------------------------------------------------------------
    // SC7: Eigener SharedCal-Termin → NICHT in toDownload (accountName-Filter)
    // -------------------------------------------------------------------------

    @Test
    fun `SC7 own shared calendar appointment is not included in toDownload`() {
        val calId = "sc-cal-7"
        createSharedCalendar(calId, ownerUsername = TEST_ACCOUNT)

        // TEST_ACCOUNT uploads own appointment to the shared calendar
        appointmentService.processBatch(listOf(apt("s7-own", sharedCalendarId = calId)), TEST_ACCOUNT)

        // Empty local list — own appointment must NOT be re-downloaded
        val body = manifest(appointments = emptyList(), confirmedEmpty = true, type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDownload", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // SC8: Kontakt auf Server neuer als auf Phone → in contacts.toUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `SC8 server contact newer than phone version is listed in contacts toUpdate`() {
        contactRepository.save(
            ContactNode(
                syncId = "c1",
                lookupKey = "lk-c1",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 200L,
                createdAt = 100L,
                displayName = "Server Version",
            )
        )

        // Phone has c1 but with older lastUpdatedAt=100 — server has 200
        val body = manifest(contacts = listOf(SyncEntry("c1", 100L)), type = "contacts")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contacts.toUpdate[0].syncId").value("c1"))
    }

    // -------------------------------------------------------------------------
    // SC9: Slot-Cache nach G3-Dedup-Archivierung invalidiert
    // -------------------------------------------------------------------------

    @Test
    fun `SC9 slot cache is invalidated after G3 dedup archives a duplicate`() {
        val title = "SC9-Duplikat"
        val dtStart = 3_000_000L
        val dtEnd   = 3_003_600L

        appointmentService.processBatch(listOf(apt("s9a", title = title, dtStart = dtStart, dtEnd = dtEnd)), TEST_ACCOUNT)
        Thread.sleep(20) // ensure distinct createdAt — dedup keeps minByOrNull { createdAt }
        appointmentService.processBatch(listOf(apt("s9b", title = title, dtStart = dtStart, dtEnd = dtEnd)), TEST_ACCOUNT)

        // Pre-seed slot cache
        val cacheKey = "slots:$TEST_ACCOUNT:Europe/Berlin:2026-05-01"
        stringRedisTemplate.opsForValue().set(cacheKey, "[]")
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue()

        // Phone sends both — G3 dedup fires (same DedupKey), archives s9b, invalidates cache
        val body = manifest(
            appointments = listOf(SyncEntry("s9a", 200L), SyncEntry("s9b", 200L)),
            type = "appointments",
        )

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk)

        assertThat(stringRedisTemplate.hasKey(cacheKey))
            .describedAs("slot cache must be invalidated after G3 dedup archives a duplicate")
            .isFalse()
    }

    // -------------------------------------------------------------------------
    // SM-M1: Gerät 2 hat andere Termine als Server → Merge: toDownload + toUpload, kein Archivieren
    // -------------------------------------------------------------------------

    @Test
    fun `SM-M1 second device with different appointments downloads server appointments and uploads its own`() {
        // Device 1 has already synced: server has x1 and y1
        appointmentService.processBatch(listOf(apt("x1"), apt("y1")), TEST_ACCOUNT)

        // Device 2 has p1 and q1 — completely different from server's x1 and y1
        val body = manifest(
            appointments = listOf(SyncEntry("p1", 100L), SyncEntry("q1", 100L)),
            type = "appointments",
        )

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            // Phone's appointments absent from server must be queued for upload
            .andExpect(jsonPath("$.appointments.toUpload", hasSize<Any>(2)))
            // Server appointments missing from phone must be downloaded — NOT archived
            .andExpect(jsonPath("$.appointments.toDownload", hasSize<Any>(2)))

        // Server appointments must NOT be soft-archived
        val x1 = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "x1")
        val y1 = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, "y1")
        assertThat(x1?.deletedAt).describedAs("x1 must NOT be archived — merge model").isNull()
        assertThat(y1?.deletedAt).describedAs("y1 must NOT be archived — merge model").isNull()
    }

    // -------------------------------------------------------------------------
    // SM-M2: Termin auf Phone älter als auf Server → toUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `SM-M2 phone appointment older than server version is returned in toUpdate not toDownload`() {
        // Server has x1 with lastUpdatedAt=200L (apt() default)
        appointmentService.processBatch(listOf(apt("x1")), TEST_ACCOUNT)

        // Phone has x1 but with older timestamp (100 < 200)
        val body = manifest(
            appointments = listOf(SyncEntry("x1", 100L)),
            type = "appointments",
        )

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toUpdate[0].syncId").value("x1"))
            .andExpect(jsonPath("$.appointments.toDownload", hasSize<Any>(0)))
            .andExpect(jsonPath("$.appointments.toUpload", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // SC-TBS-1: Explizit gelöschter Kontakt → toDeleteLocally, NICHT in toUpload
    // -------------------------------------------------------------------------

    @Test
    fun `SC-TBS-1 explicitly deleted contact is returned in toDeleteLocally and excluded from toUpload`() {
        val syncId = "tbs-k1"
        val node = contactRepository.save(
            ContactNode(
                syncId = syncId,
                lookupKey = "lk-tbs-k1",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 100L,
                createdAt = 100L,
                displayName = "To Be Deleted",
                deletedAt = null,
            )
        )
        contactRepository.setDeletedAt(node.id!!, System.currentTimeMillis())

        val body = manifest(contacts = listOf(SyncEntry(syncId, 100L)), type = "contacts")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contacts.toDeleteLocally[0]").value(syncId))
            .andExpect(jsonPath("$.contacts.toUpload", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // SC-TBS-1b: Aktualisierter Kontakt (alte Version archiviert, neue aktiv) → NICHT in toDeleteLocally
    // -------------------------------------------------------------------------

    @Test
    fun `SC-TBS-1b updated contact with archived old version is not in toDeleteLocally`() {
        val syncId = "tbs-k2"
        val now = System.currentTimeMillis()
        // Old version (archived — simulates what ContactsController does on update)
        contactRepository.save(
            ContactNode(
                syncId = syncId,
                lookupKey = "lk-tbs-k2",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 100L,
                createdAt = 50L,
                displayName = "Old Version",
                deletedAt = now - 1000,
            )
        )
        // New active version (same syncId)
        contactRepository.save(
            ContactNode(
                syncId = syncId,
                lookupKey = "lk-tbs-k2",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 200L,
                createdAt = 50L,
                displayName = "New Version",
                deletedAt = null,
            )
        )

        val body = manifest(contacts = listOf(SyncEntry(syncId, 100L)), type = "contacts")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contacts.toDeleteLocally", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // SC-TBS-2: Explizit gelöschter Termin → toDeleteLocally, NICHT in toUpload
    // -------------------------------------------------------------------------

    @Test
    fun `SC-TBS-2 explicitly deleted appointment is returned in toDeleteLocally and excluded from toUpload`() {
        val syncId = "tbs-s1"
        appointmentService.processBatch(listOf(apt(syncId)), TEST_ACCOUNT)
        val node = appointmentRepository.findCurrentOrArchivedBySyncId(TEST_ACCOUNT, syncId)!!
        appointmentRepository.softArchiveById(node.id!!, System.currentTimeMillis())

        val body = manifest(appointments = listOf(SyncEntry(syncId, 100L)), type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDeleteLocally[0]").value(syncId))
            .andExpect(jsonPath("$.appointments.toUpload", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // SC-TBS-2b: Aktiver Termin → NICHT in toDeleteLocally
    // -------------------------------------------------------------------------

    @Test
    fun `SC-TBS-2b active appointment is not in toDeleteLocally`() {
        val syncId = "tbs-s2"
        appointmentService.processBatch(listOf(apt(syncId)), TEST_ACCOUNT)

        val body = manifest(appointments = listOf(SyncEntry(syncId, 100L)), type = "appointments")

        mockMvc.perform(
            post("/sync/manifest")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.appointments.toDeleteLocally", hasSize<Any>(0)))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun manifest(
        contacts: List<SyncEntry> = emptyList(),
        appointments: List<SyncEntry> = emptyList(),
        confirmedEmpty: Boolean = false,
        type: String = "all",
    ) = ManifestRequest(
        accountName = TEST_ACCOUNT,
        contacts = contacts,
        appointments = appointments,
        confirmedEmpty = confirmedEmpty,
        type = type,
    )

    private fun apt(
        syncId: String,
        title: String = "Test Termin",
        dtStart: Long = 1_000_000L,
        dtEnd: Long = 1_003_600L,
        sharedCalendarId: String? = null,
    ) = AppointmentDtoRequest(
        syncId = syncId,
        title = title,
        dtStart = dtStart,
        dtEnd = dtEnd,
        allDay = false,
        timezone = "Europe/Berlin",
        calendarName = "TestKal",
        lastUpdatedAt = 200L,
        sharedCalendarId = sharedCalendarId,
    )

    private fun createSharedCalendar(calendarId: String, ownerUsername: String) {
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

    private fun addMember(calendarId: String, memberUsername: String) {
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

    companion object {
        const val TEST_ACCOUNT = "testuser"
        const val TEST_TOKEN = "sc-test-token"

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
