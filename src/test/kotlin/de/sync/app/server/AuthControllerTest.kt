package de.sync.app.server

import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import de.sync.app.server.cache.SessionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [WebMvcConfig::class, TokenAuthInterceptor::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest : EndpointTestSupport() {

    @MockBean
    lateinit var sessionRepository: SessionRepository

    @MockBean
    lateinit var accountRepository: AccountRepository

    @MockBean
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `register returns 200`() {
        Mockito.`when`(accountRepository.existsByUsername(TEST_ACCOUNT)).thenReturn(false)
        Mockito.`when`(passwordEncoder.encode("secret")).thenReturn("hash")

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$TEST_ACCOUNT","password":"secret"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `login returns 200`() {
        Mockito.`when`(accountRepository.findByUsername(TEST_ACCOUNT))
            .thenReturn(AccountNode(username = TEST_ACCOUNT, passwordHash = "hash"))
        Mockito.`when`(passwordEncoder.matches("secret", "hash")).thenReturn(true)

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"$TEST_ACCOUNT","password":"secret"}""")
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `logout returns 200`() {
        mockMvc.perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$TEST_TOKEN"}""")
        )
            .andExpect(status().isOk)
    }
}
