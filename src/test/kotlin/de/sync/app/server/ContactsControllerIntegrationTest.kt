package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.ContactRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
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
class ContactsControllerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var contactRepository: ContactRepository
    @Autowired private lateinit var sessionRepository: SessionRepository
    @Autowired private lateinit var driver: Driver

    @MockBean private lateinit var slotService: SlotService

    @BeforeEach
    fun setup() {
        contactRepository.deleteAll()
        sessionRepository.deleteAll()
        sessionRepository.save(SessionEntity(token = TEST_TOKEN, accountName = TEST_ACCOUNT))
        sessionRepository.save(SessionEntity(token = BOB_TOKEN, accountName = BOB_ACCOUNT))
    }

    // -------------------------------------------------------------------------
    // CC1: Newer lastUpdatedAt overwrites existing contact
    // -------------------------------------------------------------------------

    @Test
    fun `CC1 upload with newer lastUpdatedAt overwrites existing contact`() {
        postContacts(TEST_TOKEN, contact("s1", "lk-1", 100L, "Alice"))

        val result = mockMvc.perform(
            post("/contacts")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    ContactBatchRequest(contacts = listOf(contact("s1", "lk-1", 200L, "Alice Updated")))
                ))
        )
            .andExpect(status().isOk)
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, BackupResponse::class.java)
        assertThat(response.contactsStored).isEqualTo(1)

        val active = contactRepository.findAllByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().displayName).isEqualTo("Alice Updated")
    }

    // -------------------------------------------------------------------------
    // CC2: Older lastUpdatedAt is skipped
    // -------------------------------------------------------------------------

    @Test
    fun `CC2 upload with older lastUpdatedAt is skipped — original contact unchanged`() {
        postContacts(TEST_TOKEN, contact("s1", "lk-1", 200L, "Alice"))

        val result = mockMvc.perform(
            post("/contacts")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    ContactBatchRequest(contacts = listOf(contact("s1", "lk-1", 100L, "Stale")))
                ))
        )
            .andExpect(status().isOk)
            .andReturn()

        val response = objectMapper.readValue(result.response.contentAsString, BackupResponse::class.java)
        assertThat(response.contactsStored).isEqualTo(0)

        val active = contactRepository.findAllByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().displayName).isEqualTo("Alice")
    }

    // -------------------------------------------------------------------------
    // CC5: Multiple notes survive upload/download round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `CC5 multiple notes survive upload and GET contacts round-trip`() {
        val notes = listOf("First note", "Second note", "Third note")
        postContacts(TEST_TOKEN, contact("s1", "lk-1", 100L, "Alice", notes))

        mockMvc.perform(get("/contacts").header("X-Sync-Token", TEST_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contacts[0].notes.length()").value(3))
            .andExpect(jsonPath("$.contacts[0].notes[0]").value("First note"))
            .andExpect(jsonPath("$.contacts[0].notes[1]").value("Second note"))
            .andExpect(jsonPath("$.contacts[0].notes[2]").value("Third note"))
    }

    // -------------------------------------------------------------------------
    // CC6: GET /contacts returns only own account's contacts
    // -------------------------------------------------------------------------

    @Test
    fun `CC6 GET contacts returns only the requesting account's contacts`() {
        postContacts(TEST_TOKEN, contact("s1", "lk-a1", 100L, "Alice 1"))
        postContacts(TEST_TOKEN, contact("s2", "lk-a2", 100L, "Alice 2"))
        postContacts(BOB_TOKEN, contact("s3", "lk-b1", 100L, "Bob 1"))

        mockMvc.perform(get("/contacts").header("X-Sync-Token", TEST_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountName").value(TEST_ACCOUNT))
            .andExpect(jsonPath("$.contacts.length()").value(2))

        mockMvc.perform(get("/contacts").header("X-Sync-Token", BOB_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountName").value(BOB_ACCOUNT))
            .andExpect(jsonPath("$.contacts.length()").value(1))
    }

    // -------------------------------------------------------------------------
    // CC7: GET /contacts/count — account isolation on count endpoint
    // -------------------------------------------------------------------------

    @Test
    fun `CC7 GET contacts count returns only own account's contact count`() {
        postContacts(TEST_TOKEN, contact("s1", "lk-a1", 100L, "Alice 1"))
        postContacts(TEST_TOKEN, contact("s2", "lk-a2", 100L, "Alice 2"))
        postContacts(BOB_TOKEN, contact("s3", "lk-b1", 100L, "Bob 1"))
        postContacts(BOB_TOKEN, contact("s4", "lk-b2", 100L, "Bob 2"))
        postContacts(BOB_TOKEN, contact("s5", "lk-b3", 100L, "Bob 3"))

        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", TEST_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountName").value(TEST_ACCOUNT))
            .andExpect(jsonPath("$.count").value(2))

        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", BOB_TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accountName").value(BOB_ACCOUNT))
            .andExpect(jsonPath("$.count").value(3))
    }

    // -------------------------------------------------------------------------
    // CC8: findByLookupKey with duplicate nodes must not throw
    // -------------------------------------------------------------------------

    /**
     * S-B8: ContactRepository.findByLookupKey has no LIMIT 1.
     * If two Contact nodes share the same lookupKey (possible when the unique
     * constraint is absent or bypassed), the derived SDN6 query throws
     * IncorrectResultSizeDataAccessException — crashing the upload transaction.
     * Fix: add @Query with LIMIT 1 to ContactRepository.findByLookupKey.
     */
    @Test
    fun `CC8 findByLookupKey with duplicate nodes does not throw IncorrectResultSizeDataAccessException`() {
        // Bypass the API to create two Contact nodes with identical lookupKey
        driver.session().use { session ->
            session.run(
                """
                CREATE (:Contact {
                    lookupKey: 'lk-dup', accountName: ${'$'}acc,
                    syncId: 'cc8-sid-a', versionId: randomUUID(),
                    displayName: 'Alice', lastUpdatedAt: 100, createdAt: 0
                })
                CREATE (:Contact {
                    lookupKey: 'lk-dup', accountName: ${'$'}acc,
                    syncId: 'cc8-sid-b', versionId: randomUUID(),
                    displayName: 'Alice 2', lastUpdatedAt: 200, createdAt: 0
                })
                """.trimIndent(),
                mapOf("acc" to TEST_ACCOUNT),
            )
        }

        // Must not throw — should return exactly one result (after LIMIT 1 fix)
        val result = contactRepository.findByLookupKey("lk-dup")
        assertThat(result).isNotNull
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun postContacts(token: String, vararg contacts: ContactDtoRequest) {
        mockMvc.perform(
            post("/contacts")
                .header("X-Sync-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    ContactBatchRequest(contacts = contacts.toList())
                ))
        ).andExpect(status().isOk)
    }

    private fun contact(
        syncId: String,
        lookupKey: String,
        lastUpdatedAt: Long,
        displayName: String,
        notes: List<String> = emptyList(),
    ) = ContactDtoRequest(
        syncId = syncId,
        lookupKey = lookupKey,
        lastUpdatedAt = lastUpdatedAt,
        displayName = displayName,
        notes = notes,
    )

    companion object {
        const val TEST_ACCOUNT = "testuser"
        const val BOB_ACCOUNT  = "bob"
        const val TEST_TOKEN   = "cci-test-token"
        const val BOB_TOKEN    = "cci-bob-token"

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
