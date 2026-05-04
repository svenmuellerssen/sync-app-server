package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.graph.BookingNode
import de.sync.app.server.graph.BookingRepository
import de.sync.app.server.graph.ContactNode
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarNode
import de.sync.app.server.graph.SharedCalendarRepository
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
class BookingIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var sessionRepository: SessionRepository
    @Autowired private lateinit var accountRepository: AccountRepository
    @Autowired private lateinit var sharedCalendarRepository: SharedCalendarRepository
    @Autowired private lateinit var bookingRepository: BookingRepository
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var stringRedisTemplate: StringRedisTemplate
    @Autowired private lateinit var driver: Driver

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        stringRedisTemplate.keys("slots:*").takeIf { it.isNotEmpty() }?.let { stringRedisTemplate.delete(it) }
        sessionRepository.save(SessionEntity(token = TEST_TOKEN, accountName = TEST_ACCOUNT))
    }

    // -------------------------------------------------------------------------
    // BCi1: Overlapping booking → 409 Conflict
    // -------------------------------------------------------------------------

    @Test
    fun `BCi1 creating overlapping booking returns 409`() {
        val (account, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "bci1-cal")

        val body1 = createBookingRequest(startTime = 1_000_000L, endTime = 2_000_000L, calendarId = sharedCal.calendarId)
        mockMvc.perform(
            post("/booking")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body1))
        ).andExpect(status().isCreated)

        // Overlapping: startTime < previous endTime AND endTime > previous startTime
        val body2 = createBookingRequest(startTime = 1_500_000L, endTime = 2_500_000L, calendarId = sharedCal.calendarId)
        mockMvc.perform(
            post("/booking")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body2))
        ).andExpect(status().isConflict)
    }

    // -------------------------------------------------------------------------
    // BCi2: Update booking to same time → no 409 (self-exclude in overlap check)
    // -------------------------------------------------------------------------

    @Test
    fun `BCi2 updating booking to same time range does not conflict with itself`() {
        val (account, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "bci2-cal")

        val booking = bookingRepository.save(
            BookingNode(
                bookingId = UUID.randomUUID().toString(),
                accountName = TEST_ACCOUNT,
                title = "BCi2 Booking",
                startTime = 1_000_000L,
                endTime = 2_000_000L,
                sharedCalendar = sharedCal,
                creator = account,
            )
        )

        val body = UpdateBookingRequest(
            title = "BCi2 Booking Updated",
            startTime = 1_000_000L,  // same time range as existing booking
            endTime = 2_000_000L,
            sharedCalendarId = sharedCal.calendarId,
        )

        // Overlap check filters out the booking itself (.filter { it.id != existing.id })
        mockMvc.perform(
            put("/booking/${booking.id}")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isOk)
    }

    // -------------------------------------------------------------------------
    // BCi3: SharedCal member can post a message to owner's booking
    // -------------------------------------------------------------------------

    @Test
    fun `BCi3 shared calendar member can post message to owners booking`() {
        val (testAccount, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "bci3-cal")

        val booking = bookingRepository.save(
            BookingNode(
                bookingId = UUID.randomUUID().toString(),
                accountName = TEST_ACCOUNT,
                title = "BCi3 Booking",
                startTime = 1_000_000L,
                endTime = 2_000_000L,
                sharedCalendar = sharedCal,
                creator = testAccount,
            )
        )

        // Create bob and add as MEMBER_OF AFTER saving the booking.
        // bookingRepository.save() cascades through sharedCal — if sharedCal.members is empty
        // at save time, SDN6 would delete any MEMBER_OF edges created before the save.
        createMemberAccount("bob", sharedCal.calendarId)
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = "bob"))

        val body = CreateBookingMessageRequest(text = "Hi from bob")

        mockMvc.perform(
            post("/booking/${booking.id}/message")
                .header("X-Sync-Token", BOB_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andExpect(status().isCreated)
    }

    // -------------------------------------------------------------------------
    // BCi4: Invitee contact from different account → filtered, 207 Multi-Status
    // -------------------------------------------------------------------------

    @Test
    fun `BCi4 invitee contact from different account is filtered and returns 207`() {
        val (testAccount, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "bci4-cal")

        // Alice's contact — belongs to account "alice", NOT to TEST_ACCOUNT
        contactRepository.save(
            ContactNode(
                syncId = UUID.randomUUID().toString(),
                lookupKey = "lk-alice",
                accountName = "alice",
                lastUpdatedAt = 100L,
                createdAt = 100L,
                displayName = "Alice Test",
            )
        )

        val booking = bookingRepository.save(
            BookingNode(
                bookingId = UUID.randomUUID().toString(),
                accountName = TEST_ACCOUNT,
                title = "BCi4 Booking",
                startTime = 1_000_000L,
                endTime = 2_000_000L,
                sharedCalendar = sharedCal,
                creator = testAccount,
            )
        )

        val body = AddInviteesRequest(contactIds = listOf("lk-alice"))

        mockMvc.perform(
            post("/booking/${booking.id}/invitees")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isMultiStatus)
            .andExpect(jsonPath("$.filtered").value(1))
            .andExpect(jsonPath("$.saved").value(0))
    }

    // -------------------------------------------------------------------------
    // BCi5: SharedCalendar soft-deleted → booking returns 404
    // -------------------------------------------------------------------------

    @Test
    fun `BCi5 booking in soft-deleted shared calendar returns 404`() {
        val (testAccount, sharedCal) = createAccountAndSharedCal(TEST_ACCOUNT, "bci5-cal")

        val booking = bookingRepository.save(
            BookingNode(
                bookingId = UUID.randomUUID().toString(),
                accountName = TEST_ACCOUNT,
                title = "BCi5 Booking",
                startTime = 1_000_000L,
                endTime = 2_000_000L,
                sharedCalendar = sharedCal,
                creator = testAccount,
            )
        )

        // Soft-delete the shared calendar
        sharedCalendarRepository.softDelete(sharedCal.calendarId, System.currentTimeMillis())

        // The booking's sharedCalendar.deletedAt is now non-null → requireBooking throws 404
        mockMvc.perform(
            get("/booking/${booking.id}")
                .header("X-Sync-Token", TEST_TOKEN)
        ).andExpect(status().isNotFound)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a testuser account and a shared calendar owned by that account. Returns both. */
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

    /** Adds a new account as MEMBER_OF the given shared calendar. */
    private fun createMemberAccount(username: String, calendarId: String) {
        accountRepository.save(AccountNode(username = username, passwordHash = "hash"))
        sharedCalendarRepository.addMemberByUsername(calendarId, username)
    }

    private fun createBookingRequest(
        title: String = "Test Booking",
        startTime: Long,
        endTime: Long,
        calendarId: String,
    ) = CreateBookingRequest(
        title = title,
        startTime = startTime,
        endTime = endTime,
        sharedCalendarId = calendarId,
    )

    companion object {
        const val TEST_ACCOUNT = "testuser"
        const val TEST_TOKEN = "bci-test-token"
        const val BOB_TOKEN = "bci-bob-token"

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
