package de.sync.app.server

import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.given
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class TokenAuthInterceptorTest {

    @Mock
    private lateinit var sessionRepository: SessionRepository

    private lateinit var interceptor: TokenAuthInterceptor
    private lateinit var response: MockHttpServletResponse

    @BeforeEach
    fun setup() {
        interceptor = TokenAuthInterceptor(sessionRepository)
        response = MockHttpServletResponse()
    }

    // TI1: kein Header → 401, Redis wird nicht angefragt
    @Test
    fun `missing X-Sync-Token header returns 401`() {
        val request = MockHttpServletRequest()

        val result = interceptor.preHandle(request, response, Any())

        assertThat(result).isFalse()
        assertThat(response.status).isEqualTo(401)
        verifyNoInteractions(sessionRepository)
    }

    // TI2: Token nicht in Redis → 401
    @Test
    fun `unknown token returns 401`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Sync-Token", "not-a-real-token")
        given(sessionRepository.findById("not-a-real-token")).willReturn(Optional.empty())

        val result = interceptor.preHandle(request, response, Any())

        assertThat(result).isFalse()
        assertThat(response.status).isEqualTo(401)
    }

    // TI3a: gültiges Token → preHandle gibt true zurück
    @Test
    fun `valid token allows request through`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Sync-Token", "valid-token")
        given(sessionRepository.findById("valid-token"))
            .willReturn(Optional.of(SessionEntity(token = "valid-token", accountName = "alice")))

        val result = interceptor.preHandle(request, response, Any())

        assertThat(result).isTrue()
        assertThat(response.status).isEqualTo(200)
    }

    // TI3b: gültiges Token → accountName-Attribut gesetzt, damit Controller es lesen kann
    @Test
    fun `valid token sets accountName request attribute`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Sync-Token", "valid-token")
        given(sessionRepository.findById("valid-token"))
            .willReturn(Optional.of(SessionEntity(token = "valid-token", accountName = "alice")))

        interceptor.preHandle(request, response, Any())

        assertThat(request.getAttribute("accountName")).isEqualTo("alice")
    }
}
