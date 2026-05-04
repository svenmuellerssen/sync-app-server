package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.AccountRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var accountRepository: AccountRepository
    @Autowired private lateinit var sessionRepository: SessionRepository

    @BeforeEach
    fun setup() {
        sessionRepository.deleteAll()
        accountRepository.deleteAll()
    }

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    @Test
    fun `register creates account and returns token with expiry`() {
        val before = System.currentTimeMillis()

        val result = mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"secret"}""")
        )
            .andExpect(status().isOk)
            .andReturn()

        val after = System.currentTimeMillis()
        val body = objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)

        assertThat(body.token).isNotBlank()
        assertThat(body.expiresAt).isBetween(
            before + TimeUnit.HOURS.toMillis(24),
            after  + TimeUnit.HOURS.toMillis(24) + 5_000,
        )

        // Persistence: account stored in Neo4j, session stored in Redis
        assertThat(accountRepository.existsByUsername("alice")).isTrue()
        assertThat(sessionRepository.findById(body.token)).isPresent
    }

    @Test
    fun `register with duplicate username returns 409`() {
        register("alice", "secret")

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"other"}""")
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `register with blank username returns 400`() {
        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"","password":"secret"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `register with blank password returns 400`() {
        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":""}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `register with missing fields returns 400`() {
        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice"}""")
        )
            .andExpect(status().isBadRequest)
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    @Test
    fun `login returns token for valid credentials`() {
        register("alice", "secret")

        val before = System.currentTimeMillis()

        val result = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"secret"}""")
        )
            .andExpect(status().isOk)
            .andReturn()

        val after = System.currentTimeMillis()
        val body = objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java)

        assertThat(body.token).isNotBlank()
        assertThat(body.expiresAt).isBetween(
            before + TimeUnit.HOURS.toMillis(24),
            after  + TimeUnit.HOURS.toMillis(24) + 5_000,
        )

        // Session stored in Redis
        assertThat(sessionRepository.findById(body.token)).isPresent
    }

    @Test
    fun `login with wrong password returns 401`() {
        register("alice", "secret")

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"wrong"}""")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `login with unknown user returns 401`() {
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"nobody","password":"secret"}""")
        )
            .andExpect(status().isUnauthorized)
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    @Test
    fun `logout invalidates token — subsequent requests return 401`() {
        val token = register("alice", "secret")

        // Token works before logout
        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", token))
            .andExpect(status().isOk)

        // Logout — must present the token to be allowed through the interceptor
        mockMvc.perform(post("/auth/logout").header("X-Sync-Token", token))
            .andExpect(status().isOk)

        // Token no longer accepted — TokenAuthInterceptor rejects it
        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", token))
            .andExpect(status().isUnauthorized)

        // Session gone from Redis
        assertThat(sessionRepository.findById(token)).isEmpty
    }

    @Test
    fun `logout without token returns 401`() {
        // /auth/logout is protected — callers without a valid token are rejected.
        mockMvc.perform(post("/auth/logout"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout with unknown token returns 401`() {
        // TokenAuthInterceptor rejects the call before it reaches the controller.
        mockMvc.perform(post("/auth/logout").header("X-Sync-Token", "does-not-exist"))
            .andExpect(status().isUnauthorized)
    }

    // -------------------------------------------------------------------------
    // Token auth on protected endpoints
    // -------------------------------------------------------------------------

    @Test
    fun `request without token returns 401`() {
        mockMvc.perform(get("/contacts/count"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `request with unknown token returns 401`() {
        mockMvc.perform(
            get("/contacts/count").header("X-Sync-Token", "not-a-real-token")
        )
            .andExpect(status().isUnauthorized)
    }

    // -------------------------------------------------------------------------
    // Auth5: Redis TTL expiry — expired token returns 401
    // -------------------------------------------------------------------------

    @Test
    fun `Auth5 session token expired via Redis TTL is rejected with 401`() {
        // Save a session with a 1-second TTL — Redis will evict it automatically.
        sessionRepository.save(
            SessionEntity(token = "expiring-token", accountName = "ttl-user", ttlSeconds = 1L)
        )

        // Token is valid immediately after save
        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", "expiring-token"))
            .andExpect(status().isOk)

        // Wait for Redis TTL to expire
        Thread.sleep(1_100)

        // Token is now gone from Redis → 401
        mockMvc.perform(get("/contacts/count").header("X-Sync-Token", "expiring-token"))
            .andExpect(status().isUnauthorized)

        // Verify the session is truly absent from Redis
        assertThat(sessionRepository.findById("expiring-token")).isEmpty
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Registers a new account and returns the session token. */
    private fun register(username: String, password: String): String {
        val result = mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$username","password":"$password"}""")
        )
            .andExpect(status().isOk)
            .andReturn()

        return objectMapper.readValue(result.response.contentAsString, LoginResponse::class.java).token
    }

    companion object {
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
