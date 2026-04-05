package de.sync.app.server.data

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface SessionRepository : JpaRepository<SessionEntity, String> {

    /** Remove all expired sessions (server-restart cleanup). */
    @Modifying
    @Query("DELETE FROM SessionEntity s WHERE s.expiresAt < :nowMs")
    fun deleteExpired(nowMs: Long): Int
}
