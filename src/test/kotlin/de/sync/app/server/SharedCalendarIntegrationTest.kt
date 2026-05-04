package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.cache.SharedCalendarInviteEntity
import de.sync.app.server.cache.SharedCalendarInviteRepository
import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import de.sync.app.server.graph.SharedCalendarNode
import de.sync.app.server.graph.SharedCalendarRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SharedCalendarIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var sessionRepository: SessionRepository
    @Autowired private lateinit var accountRepository: AccountRepository
    @Autowired private lateinit var sharedCalendarRepository: SharedCalendarRepository
    @Autowired private lateinit var sharedCalendarInviteRepository: SharedCalendarInviteRepository
    @Autowired private lateinit var bookingRepository: BookingRepository
    @Autowired private lateinit var stringRedisTemplate: StringRedisTemplate
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        stringRedisTemplate.keys("slots:*").takeIf { it.isNotEmpty() }?.let { stringRedisTemplate.delete(it) }
        // Also flush invite codes (Redis @RedisHash) — keys look like "cal_invite:<id>"
        stringRedisTemplate.keys("cal_invite:*").takeIf { it.isNotEmpty() }?.let { stringRedisTemplate.delete(it) }
        sessionRepository.save(SessionEntity(token = TEST_TOKEN, accountName = TEST_ACCOUNT))
    }

    // -------------------------------------------------------------------------
    // SCSi1: BUG-3 sequential proof — invite code is single-use
    //        Two different accounts use the same code sequentially.
    //        Second join must fail with 404 (code consumed after first join).
    //        (Concurrent race between the two joins is not deterministically testable
    //        in a JUnit integration test; this sequential test proves the code
    //        is consumed and cannot be replayed.)
    // -------------------------------------------------------------------------

    @Test
    fun `SCSi1 BUG-3 sequential proof — second join with same invite code returns 404`() {
        val (_, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "scsi1-cal")

        // Bob and Charlie both want to use the same code — bob goes first
        val bobAccount = accountRepository.save(AccountNode(username = "bob-scsi1", passwordHash = "hash"))
        val charlieAccount = accountRepository.save(AccountNode(username = "charlie-scsi1", passwordHash = "hash"))
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = "bob-scsi1"))
        sessionRepository.save(SessionEntity(token = CHARLIE_TOKEN, accountName = "charlie-scsi1"))

        sharedCalendarInviteRepository.save(
            SharedCalendarInviteEntity(inviteCode = "CODE-SCSI1", calendarId = sharedCal.calendarId, createdBy = TEST_ACCOUNT)
        )

        // Bob joins first → 200, code is consumed
        mockMvc.perform(
            post("/shared-calendar/join")
                .header("X-Sync-Token", BOB_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":"CODE-SCSI1"}""")
        ).andExpect(status().isOk)

        // Charlie tries same code → 404 (invite code was deleted after bob's join)
        mockMvc.perform(
            post("/shared-calendar/join")
                .header("X-Sync-Token", CHARLIE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":"CODE-SCSI1"}""")
        ).andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // SCSi2: Invite code consumed after first join → same caller returns 404
    // -------------------------------------------------------------------------

    @Test
    fun `SCSi2 invite code consumed after first join — second join returns 404`() {
        val (_, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "scsi2-cal")

        val bobAccount = accountRepository.save(AccountNode(username = "bob-scsi2", passwordHash = "hash"))
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = "bob-scsi2"))

        sharedCalendarInviteRepository.save(
            SharedCalendarInviteEntity(inviteCode = "CODE-SCSI2", calendarId = sharedCal.calendarId, createdBy = TEST_ACCOUNT)
        )

        // First join — succeeds, code is consumed
        mockMvc.perform(
            post("/shared-calendar/join")
                .header("X-Sync-Token", BOB_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":"CODE-SCSI2"}""")
        ).andExpect(status().isOk)

        // Second join with the same code → 404
        mockMvc.perform(
            post("/shared-calendar/join")
                .header("X-Sync-Token", BOB_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":"CODE-SCSI2"}""")
        ).andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // SCSi3: Deleting SharedCalendar cascades to bookings → booking returns 404
    // -------------------------------------------------------------------------

    @Test
    fun `SCSi3 deleting shared calendar soft-cancels bookings — booking returns 404`() {
        val (testAccount, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "scsi3-cal")

        val booking = bookingRepository.save(
            BookingNode(
                bookingId = UUID.randomUUID().toString(),
                accountName = TEST_ACCOUNT,
                title = "SCSi3 Booking",
                startTime = 1_000_000L,
                endTime = 2_000_000L,
                sharedCalendar = sharedCal,
                creator = testAccount,
            )
        )

        // Owner deletes the calendar → cascades to bookings via softDeleteAllBySharedCalendarId
        mockMvc.perform(
            delete("/shared-calendar/${sharedCal.calendarId}")
                .header("X-Sync-Token", TEST_TOKEN)
        ).andExpect(status().isNoContent)

        // Booking has cancelledAt set → requireBooking throws 404
        mockMvc.perform(
            get("/booking/${booking.id}")
                .header("X-Sync-Token", TEST_TOKEN)
        ).andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // SCSi4: Leave → slot cache invalidated for all affected accounts
    // -------------------------------------------------------------------------

    @Test
    fun `SCSi4 leaving shared calendar invalidates slot cache for all members`() {
        createAccountAndSharedCal(TEST_ACCOUNT, "scsi4-cal")

        // Add bob as MEMBER_OF via Cypher
        driver.session().use { session ->
            session.run(
                """
                MERGE (a:Account {username: 'bob-scsi4', passwordHash: 'hash', createdAt: 0})
                WITH a
                MATCH (sc:SharedCalendar {calendarId: 'scsi4-cal'})
                MERGE (a)-[:MEMBER_OF]->(sc)
                """.trimIndent()
            )
        }
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = "bob-scsi4"))

        // Pre-seed slot cache for both accounts (key format: slots:{accountName}:Europe/Berlin:{localDate})
        val testKey = "slots:$TEST_ACCOUNT:Europe/Berlin:2030-01-01"
        val bobKey = "slots:bob-scsi4:Europe/Berlin:2030-01-01"
        stringRedisTemplate.opsForValue().set(testKey, "[]")
        stringRedisTemplate.opsForValue().set(bobKey, "[]")

        assertThat(stringRedisTemplate.hasKey(testKey)).isTrue()
        assertThat(stringRedisTemplate.hasKey(bobKey)).isTrue()

        // Bob leaves the shared calendar
        mockMvc.perform(
            delete("/shared-calendar/scsi4-cal/leave")
                .header("X-Sync-Token", BOB_TOKEN)
        ).andExpect(status().isNoContent)

        // Both slot cache entries must be gone (SlotService.invalidateAccount called for all affected accounts)
        assertThat(stringRedisTemplate.hasKey(testKey)).isFalse()
        assertThat(stringRedisTemplate.hasKey(bobKey)).isFalse()
    }

    // -------------------------------------------------------------------------
    // SCSi5: GET /list returns only calendars accessible to the caller
    // -------------------------------------------------------------------------

    @Test
    fun `SCSi5 list returns only calendars accessible to caller`() {
        // testuser owns sc-5a
        createAccountAndSharedCal(TEST_ACCOUNT, "scsi5-cal-a")

        // alice owns sc-5b — testuser is NOT a member
        driver.session().use { session ->
            session.run(
                """
                MERGE (alice:Account {username: 'alice-scsi5', passwordHash: 'hash', createdAt: 0})
                CREATE (sc:SharedCalendar {calendarId: 'scsi5-cal-b', name: 'Alices Calendar', createdBy: 'alice-scsi5', createdAt: 0, color: 0})
                CREATE (alice)-[:OWNS_CALENDAR]->(sc)
                """.trimIndent()
            )
        }

        mockMvc.perform(
            get("/shared-calendar/list")
                .header("X-Sync-Token", TEST_TOKEN)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(1)))
            .andExpect(jsonPath("$[0].calendarId").value("scsi5-cal-a"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createAccountAndSharedCal(username: String, calendarId: String): Pair<AccountNode, SharedCalendarNode> {
        val account = accountRepository.save(AccountNode(username = username, passwordHash = "hash"))
        val sharedCal = sharedCalendarRepository.save(
            SharedCalendarNode(
                calendarId = calendarId,
                name = "Test Calendar ($calendarId)",
                createdBy = username,
                owner = account,
            )
        )
        return account to sharedCal
    }

    companion object {
        const val TEST_ACCOUNT = "testuser"
        const val TEST_TOKEN = "scsi-test-token"
        const val BOB_TOKEN = "scsi-bob-token"
        const val CHARLIE_TOKEN = "scsi-charlie-token"

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
