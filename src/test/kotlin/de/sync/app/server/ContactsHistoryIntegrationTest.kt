package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.ContactNode
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContactsHistoryIntegrationTest {

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
    }

    @Test
    fun `should save contact versions with same syncId when a contact is updated`() {
        contactRepository.save(
            ContactNode(
                syncId = "sync-1",
                lookupKey = "lk-1",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 100L,
                createdAt = 100L,
                displayName = "Old Name",
            )
        )

        val body = ContactBatchRequest(
            contacts = listOf(
                ContactDtoRequest(
                    syncId = "sync-1",
                    lookupKey = "lk-1",
                    lastUpdatedAt = 200L,
                    displayName = "New Name",
                )
            )
        )

        mockMvc.perform(
            post("/contacts")
                .header("X-Sync-Token", TEST_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        )
            .andExpect(status().isOk)

        // Only one active (non-deleted) contact for the account
        val active = contactRepository.findAllByAccountNameAndDeletedAtIsNull(TEST_ACCOUNT)
        assertThat(active).hasSize(1)
        assertThat(active.single().displayName).isEqualTo("New Name")

        // Two contact nodes exist with syncId "sync-1" (old + new)
        assertThat(countContactsBySyncId("sync-1")).isEqualTo(2L)

        // One PREVIOUS_VERSION edge links new → old
        assertThat(countPreviousVersionEdges("sync-1")).isEqualTo(1L)
    }

    @Test
    fun `should return only requesting account contacts when loading by syncId list`() {
        contactRepository.save(
            ContactNode(
                syncId = "shared-sync",
                lookupKey = "lk-alice",
                accountName = TEST_ACCOUNT,
                lastUpdatedAt = 100L,
                createdAt = 100L,
                displayName = "Alice",
            )
        )
        contactRepository.save(
            ContactNode(
                syncId = "shared-sync",
                lookupKey = "lk-bob",
                accountName = "bob",
                lastUpdatedAt = 200L,
                createdAt = 200L,
                displayName = "Bob",
            )
        )

        val result = contactRepository.findAllBySyncIdIn(TEST_ACCOUNT, listOf("shared-sync"))
        assertThat(result).hasSize(1)
        assertThat(result.single().accountName).isEqualTo(TEST_ACCOUNT)
    }

    private fun countContactsBySyncId(syncId: String): Long =
        driver.session().use { session ->
            session.run(
                "MATCH (c:Contact {syncId: \$syncId}) RETURN count(c) AS count",
                mapOf("syncId" to syncId),
            ).single()["count"].asLong()
        }

    private fun countPreviousVersionEdges(syncId: String): Long =
        driver.session().use { session ->
            session.run(
                "MATCH (new:Contact {syncId: \$syncId})-[:PREVIOUS_VERSION]->(old:Contact {syncId: \$syncId}) RETURN count(*) AS count",
                mapOf("syncId" to syncId),
            ).single()["count"].asLong()
        }

    companion object {
        const val TEST_ACCOUNT = "testuser"
        const val TEST_TOKEN = "test-token"

        @Container @JvmStatic
        val neo4j: Neo4jContainer<*> = Neo4jContainer("neo4j:5")
            .withoutAuthentication()

        @Container @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

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
