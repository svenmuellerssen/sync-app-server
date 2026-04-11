package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * Redis template configuration.
 *
 * Two templates are provided:
 *
 * 1. [StringRedisTemplate] — auto-configured by Spring Boot, available for injection.
 *    Use for simple String key/value operations (e.g. caching counts, flags).
 *
 * 2. [RedisTemplate<String, Any>] ("jsonRedisTemplate") — keys are Strings,
 *    values are serialized as JSON via Jackson. Use for storing objects or lists
 *    (e.g. the slot-service day-buckets from homeservice).
 *
 * Usage in a service:
 *   @Autowired lateinit var redis: StringRedisTemplate
 *   redis.opsForValue().set("key", "value")
 *   redis.opsForValue().get("key")
 *
 *   @Qualifier("jsonRedisTemplate")
 *   @Autowired lateinit var jsonRedis: RedisTemplate<String, Any>
 *   jsonRedis.opsForValue().set("slots:2024-03-15", listOf(...))
 *
 * Redis @RedisHash entities and CrudRepository interfaces go in the cache/ package.
 */
@Configuration
class RedisConfig {

    @Bean("jsonRedisTemplate")
    fun jsonRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val mapper = ObjectMapper().registerKotlinModule()
        val serializer = GenericJackson2JsonRedisSerializer(mapper)

        return RedisTemplate<String, Any>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer   = StringRedisSerializer()
            hashKeySerializer = StringRedisSerializer()
            valueSerializer = serializer
            hashValueSerializer = serializer
            afterPropertiesSet()
        }
    }
}
