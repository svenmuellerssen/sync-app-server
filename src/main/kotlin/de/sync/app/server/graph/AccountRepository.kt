package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository

interface AccountRepository : Neo4jRepository<AccountNode, Long> {
    fun findByUsername(username: String): AccountNode?
    fun existsByUsername(username: String): Boolean
}
