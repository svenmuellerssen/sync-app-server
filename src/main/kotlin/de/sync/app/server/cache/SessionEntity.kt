package de.sync.app.server.cache

import org.springframework.data.annotation.Id
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.TimeToLive

@RedisHash("session")
data class SessionEntity(
    @Id val token: String,
    val accountName: String,
    @TimeToLive val ttlSeconds: Long = 86400,   // 24h — Redis löscht automatisch
    val createdAt: Long = System.currentTimeMillis(),
)
