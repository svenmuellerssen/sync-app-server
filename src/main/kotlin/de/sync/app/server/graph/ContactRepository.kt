package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface ContactRepository : Neo4jRepository<ContactNode, Long> {
    fun findAllByAccountName(accountName: String): List<ContactNode>
    fun countByAccountName(accountName: String): Long
    fun findBySyncId(syncId: String): ContactNode?
    fun findByLookupKey(lookupKey: String): ContactNode?

    @Query("MATCH (c:Contact {accountName: \$accountName})-[:HAS_EMAIL]->(e:Email {address: \$email}) RETURN c")
    fun findByAccountNameAndEmail(accountName: String, email: String): ContactNode?
}
