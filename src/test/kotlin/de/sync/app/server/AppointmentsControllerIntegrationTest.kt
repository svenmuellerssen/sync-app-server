package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.AppointmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
class AppointmentsControllerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var sessionRepository: SessionRepository
    @Autowired private lateinit var appointmentRepository: AppointmentRepository
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        sessionRepository.deleteAll()
        sessionRepository.save(SessionEntity(token = ALICE_TOKEN, accountName = ALICE))
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = BOB))
    }

    // -------------------------------------------------------------------------
    // AC1: POST /appointments without X-Sync-Token returns 401
    // -------------------------------------------------------------------------

    @Test
    fun `AC1 upload appointments without token returns 401`() {
        val body = AppointmentBatchRequest(
            appointments = listOf(apt("ac1-s1"))
        )
        mockMvc.perform(
            post("/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isUnauthorized)
    }

    // -------------------------------------------------------------------------
    // AC2: GET /{syncId}/history filters versions by requesting account
    // -------------------------------------------------------------------------

    @Test
    fun `AC2 appointment history endpoint returns only own account versions`() {
        // Both alice and bob upload an appointment — each stored under their own CalendarNode.
        // syncId is intentionally different (correct production scenario) to avoid the
        // cross-account syncId collision bug; we test the filter on the history path.
        uploadAppointment(ALICE_TOKEN, syncId = "ac2-alice", calendarName = "AliceCal")
        uploadAppointment(BOB_TOKEN,   syncId = "ac2-bob",   calendarName = "BobCal")

        // Alice requests history for her own appointment: 1 version visible
        mockMvc.perform(
            get("/appointments/ac2-alice/history").header("X-Sync-Token", ALICE_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versions.length()").value(1))
            .andExpect(jsonPath("$.versions[0].syncId").value("ac2-alice"))

        // Bob requests history for Alice's syncId: 0 versions (filtered out by accountName)
        mockMvc.perform(
            get("/appointments/ac2-alice/history").header("X-Sync-Token", BOB_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versions.length()").value(0))

        // Bob requests history for his own appointment: 1 version visible
        mockMvc.perform(
            get("/appointments/ac2-bob/history").header("X-Sync-Token", BOB_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versions.length()").value(1))
    }

    // -------------------------------------------------------------------------
    // AC3: GET /appointments/count includes SharedCalendar appointments
    // -------------------------------------------------------------------------

    @Test
    fun `AC3 appointment count includes both personal and own shared-calendar appointments`() {
        val sharedCalId = "ac3-shared-cal"

        // Setup: SharedCalendar in Neo4j with alice as owner
        driver.session().use { s ->
            s.run(
                """
                MERGE (a:Account {username: ${'$'}owner, passwordHash: 'x', createdAt: 0})
                CREATE (sc:SharedCalendar {calendarId: ${'$'}calId, name: 'AC3 Cal', createdBy: ${'$'}owner, createdAt: 0})
                CREATE (a)-[:OWNS_CALENDAR]->(sc)
                """.trimIndent(),
                mapOf("owner" to ALICE, "calId" to sharedCalId),
            )
        }

        // Upload 1 personal appointment (no sharedCalendarId → creates alice's CalendarNode)
        uploadAppointment(ALICE_TOKEN, syncId = "ac3-personal", calendarName = "AliceCal")

        // Upload 1 shared-calendar appointment (linked to SharedCalendarNode)
        uploadAppointment(ALICE_TOKEN, syncId = "ac3-shared", sharedCalendarId = sharedCalId)

        // Count must be 2: 1 personal + 1 shared
        mockMvc.perform(
            get("/appointments/count").header("X-Sync-Token", ALICE_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountName").value(ALICE))
            .andExpect(jsonPath("$.count").value(2))

        // Bob's count is 0 — he has no appointments
        mockMvc.perform(
            get("/appointments/count").header("X-Sync-Token", BOB_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(0))
    }

    @Test
    fun `should count appointments only once when multiple calendars reference same appointment node`() {
        driver.session().use { s ->
            s.run(
                """
                CREATE (owner:Account {username: ${'$'}account, passwordHash: 'x', createdAt: 0})
                CREATE (cal1:CalendarNode {calendarId: 'cal-1', name: 'Cal1', calendarType: 'LOCAL', accountName: ${'$'}account})
                CREATE (cal2:CalendarNode {calendarId: 'cal-2', name: 'Cal2', calendarType: 'LOCAL', accountName: ${'$'}account})
                CREATE (a:Appointment {
                    syncId: 'dup-count-sync',
                    versionId: randomUUID(),
                    contentHash: 'hash',
                    calendarId: 'cal-1',
                    accountName: ${'$'}account,
                    title: 'Dup Count',
                    dtStart: 1000,
                    allDay: false,
                    timezone: 'Europe/Berlin',
                    lastUpdatedAt: 1000,
                    createdAt: 1000,
                    versionCreatedAt: 1000
                })
                CREATE (cal1)-[:HAS_APPOINTMENT]->(a)
                CREATE (cal2)-[:HAS_APPOINTMENT]->(a)
                """.trimIndent(),
                mapOf("account" to ALICE),
            )
        }

        mockMvc.perform(get("/appointments/count").header("X-Sync-Token", ALICE_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
    }

    @Test
    fun `should count by syncId when multiple active nodes share the same syncId`() {
        driver.session().use { s ->
            s.run(
                """
                CREATE (owner:Account {username: ${'$'}account, passwordHash: 'x', createdAt: 0})
                CREATE (cal:CalendarNode {calendarId: 'cal-syncid', name: 'Cal', calendarType: 'LOCAL', accountName: ${'$'}account})
                CREATE (a1:Appointment {
                    syncId: 'dup-syncid',
                    versionId: randomUUID(),
                    contentHash: 'hash-1',
                    calendarId: 'cal-syncid',
                    accountName: ${'$'}account,
                    title: 'Dup #1',
                    dtStart: 1000,
                    allDay: false,
                    timezone: 'Europe/Berlin',
                    lastUpdatedAt: 1000,
                    createdAt: 1000,
                    versionCreatedAt: 1000
                })
                CREATE (a2:Appointment {
                    syncId: 'dup-syncid',
                    versionId: randomUUID(),
                    contentHash: 'hash-2',
                    calendarId: 'cal-syncid',
                    accountName: ${'$'}account,
                    title: 'Dup #2',
                    dtStart: 1000,
                    allDay: false,
                    timezone: 'Europe/Berlin',
                    lastUpdatedAt: 1100,
                    createdAt: 1100,
                    versionCreatedAt: 1100
                })
                CREATE (cal)-[:HAS_APPOINTMENT]->(a1)
                CREATE (cal)-[:HAS_APPOINTMENT]->(a2)
                """.trimIndent(),
                mapOf("account" to ALICE),
            )
        }

        mockMvc.perform(get("/appointments/count").header("X-Sync-Token", ALICE_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(1))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun uploadAppointment(
        token: String,
        syncId: String,
        calendarName: String? = null,
        sharedCalendarId: String? = null,
    ) {
        val body = AppointmentBatchRequest(
            appointments = listOf(apt(syncId, calendarName, sharedCalendarId))
        )
        mockMvc.perform(
            post("/appointments")
                .header("X-Sync-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk)
    }

    private fun apt(
        syncId: String,
        calendarName: String? = "TestCal",
        sharedCalendarId: String? = null,
    ) = AppointmentDtoRequest(
        syncId = syncId,
        title = "Test Appointment",
        dtStart = 1_000_000L,
        dtEnd   = 1_003_600L,
        allDay  = false,
        timezone = "Europe/Berlin",
        calendarName = calendarName,
        sharedCalendarId = sharedCalendarId,
        lastUpdatedAt = 100L,
    )

    companion object {
        const val ALICE       = "alice"
        const val BOB         = "bob"
        const val ALICE_TOKEN = "aci-alice-token"
        const val BOB_TOKEN   = "aci-bob-token"

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
