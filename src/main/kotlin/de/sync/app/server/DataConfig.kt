package de.sync.app.server

import org.springframework.context.annotation.Configuration
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories

/**
 * Explicit repository scanning — keeps Neo4j and Redis repositories separate.
 *
 * | Package | Store  | Annotation type             |
 * |---------|--------|-----------------------------|
 * | graph/  | Neo4j  | @Node  / Neo4jRepository    |
 * | cache/  | Redis  | @RedisHash / CrudRepository |
 */
@Configuration
@EnableNeo4jRepositories(basePackages = ["de.sync.app.server.graph"])
@EnableRedisRepositories(basePackages = ["de.sync.app.server.cache"])
class DataConfig
