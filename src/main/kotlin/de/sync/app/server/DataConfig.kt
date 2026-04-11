package de.sync.app.server

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories

/**
 * Explicit repository scanning — keeps JPA, Neo4j and Redis repositories separate
 * so Spring doesn't try to apply the wrong store to the wrong interface.
 *
 * | Package              | Store  | Annotation type      |
 * |----------------------|--------|----------------------|
 * | data/                | MySQL  | @Entity / JpaRepository     |
 * | graph/               | Neo4j  | @Node  / Neo4jRepository    |
 * | cache/               | Redis  | @RedisHash / CrudRepository |
 */
@Configuration
@EnableJpaRepositories(basePackages = ["de.sync.app.server.data"])
@EnableNeo4jRepositories(basePackages = ["de.sync.app.server.graph"])
@EnableRedisRepositories(basePackages = ["de.sync.app.server.cache"])
class DataConfig
