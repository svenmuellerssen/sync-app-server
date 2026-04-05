package de.sync.app.server.data

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "sessions")
data class SessionEntity(

    @Id
    @Column(name = "token", nullable = false, length = 36)
    val token: String,

    @Column(name = "account_name", nullable = false)
    val accountName: String,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long,
)
