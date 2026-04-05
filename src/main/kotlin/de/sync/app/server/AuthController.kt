package de.sync.app.server

import de.sync.app.server.data.SessionEntity
import de.sync.app.server.data.SessionRepository
import jakarta.transaction.Transactional
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AuthController(private val sessionRepository: SessionRepository) {

    @Transactional
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<LoginResponse> {
        if (request.username.isBlank() || request.password.isBlank()) {
            return ResponseEntity.status(401).build()
        }

        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        val expiresAt = now + TOKEN_VALIDITY_MS

        sessionRepository.save(
            SessionEntity(
                token = token,
                accountName = request.username,
                expiresAt = expiresAt,
                createdAt = now,
            )
        )

        // Opportunistically clean up expired sessions for this account
        sessionRepository.deleteExpired(now)

        return ResponseEntity.ok(LoginResponse(token = token, expiresAt = expiresAt))
    }

    @Transactional
    @PostMapping("/logout")
    fun logout(@RequestBody request: LogoutRequest): ResponseEntity<Void> {
        sessionRepository.deleteById(request.token)
        return ResponseEntity.ok().build()
    }

    fun isTokenValid(token: String): Boolean {
        val session = sessionRepository.findById(token).orElse(null) ?: return false
        return System.currentTimeMillis() < session.expiresAt
    }

    companion object {
        private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String, val expiresAt: Long)
data class LogoutRequest(val token: String)
