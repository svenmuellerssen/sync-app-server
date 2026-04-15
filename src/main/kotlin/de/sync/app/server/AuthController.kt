package de.sync.app.server

import de.sync.app.server.cache.SessionEntity
import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.graph.AccountNode
import de.sync.app.server.graph.AccountRepository
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AuthController(
    private val sessionRepository: SessionRepository,
    private val accountRepository: AccountRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @PostMapping("/register")
    fun register(@RequestBody @jakarta.validation.Valid request: LoginRequest): ResponseEntity<LoginResponse> {
        if (request.username.isBlank() || request.password.isBlank())
            return ResponseEntity.status(400).build()
        if (accountRepository.existsByUsername(request.username))
            return ResponseEntity.status(409).build()

        accountRepository.save(
            AccountNode(
                username = request.username,
                passwordHash = passwordEncoder.encode(request.password),
            )
        )
        return createSession(request.username)
    }

    @PostMapping("/login")
    fun login(@RequestBody @jakarta.validation.Valid request: LoginRequest): ResponseEntity<LoginResponse> {
        if (request.username.isBlank() || request.password.isBlank())
            return ResponseEntity.status(401).build()

        val account = accountRepository.findByUsername(request.username)
            ?: return ResponseEntity.status(401).build()
        if (!passwordEncoder.matches(request.password, account.passwordHash))
            return ResponseEntity.status(401).build()

        return createSession(request.username)
    }

    @PostMapping("/logout")
    fun logout(@RequestBody request: LogoutRequest): ResponseEntity<Void> {
        sessionRepository.deleteById(request.token)
        return ResponseEntity.ok().build()
    }

    fun getAccountNameFromToken(token: String): String? =
        sessionRepository.findById(token).orElse(null)?.accountName

    private fun createSession(accountName: String): ResponseEntity<LoginResponse> {
        val token = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + TOKEN_VALIDITY_MS
        sessionRepository.save(
            SessionEntity(
                token = token,
                accountName = accountName,
                ttlSeconds = TOKEN_VALIDITY_MS / 1000,
            )
        )
        return ResponseEntity.ok(LoginResponse(token = token, expiresAt = expiresAt))
    }

    companion object {
        private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L
    }
}

data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)
data class LoginResponse(val token: String, val expiresAt: Long)
data class LogoutRequest(val token: String)

