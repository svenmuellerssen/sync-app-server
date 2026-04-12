package de.sync.app.server.cache

import org.springframework.data.annotation.Id
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.TimeToLive

@RedisHash("cal_invite")
data class SharedCalendarInviteEntity(
    @Id val inviteCode: String,
    val calendarId: String,
    val createdBy: String,
    @TimeToLive val ttlSeconds: Long = 600,
    val createdAt: Long = System.currentTimeMillis(),
)
