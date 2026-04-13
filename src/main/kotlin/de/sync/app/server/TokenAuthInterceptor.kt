package de.sync.app.server

import de.sync.app.server.cache.SessionRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

// Validates the X-Sync-Token header on all requests except /auth/ paths.
// Returns HTTP 401 if the token is missing or not found in Redis.
@Component
class TokenAuthInterceptor(private val sessionRepository: SessionRepository) : HandlerInterceptor {
    override fun preHandle(req: HttpServletRequest, res: HttpServletResponse, handler: Any): Boolean {
        val token = req.getHeader("X-Sync-Token") ?: run {
            res.status = HttpServletResponse.SC_UNAUTHORIZED
            return false
        }
        if (!sessionRepository.findById(token).isPresent) {
            res.status = HttpServletResponse.SC_UNAUTHORIZED
            return false
        }
        return true
    }
}
