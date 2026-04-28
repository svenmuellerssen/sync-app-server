package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface ContactRepository : Neo4jRepository<ContactNode, Long> {
    fun findAllByAccountNameAndDeletedAtIsNull(accountName: String): List<ContactNode>
    fun countByAccountNameAndDeletedAtIsNull(accountName: String): Long
    fun findBySyncId(syncId: String): ContactNode?
    fun findByLookupKey(lookupKey: String): ContactNode?

    @Query("MATCH (c:Contact) WHERE c.syncId IN \$syncIds AND c.deletedAt IS NULL RETURN c")
    fun findAllBySyncIdIn(syncIds: List<String>): List<ContactNode>

    @Query("MATCH (c:Contact {accountName: \$accountName}) WHERE c.lookupKey IN \$lookupKeys RETURN c")
    fun findAllByAccountNameAndLookupKeyIn(accountName: String, lookupKeys: List<String>): List<ContactNode>

    /** Only active (non-deleted) contacts — used when adding invitees to bookings. */
    @Query("MATCH (c:Contact {accountName: \$accountName}) WHERE c.lookupKey IN \$lookupKeys AND c.deletedAt IS NULL RETURN c")
    fun findAllActiveByAccountNameAndLookupKeyIn(accountName: String, lookupKeys: List<String>): List<ContactNode>

    @Query("MATCH (c:Contact {accountName: \$accountName})-[:HAS_EMAIL]->(e:Email {address: \$email}) RETURN c")
    fun findByAccountNameAndEmail(accountName: String, email: String): ContactNode?

    @Query("MATCH (new:Contact) WHERE id(new) = \$newId MATCH (old:Contact) WHERE id(old) = \$oldId MERGE (new)-[:PREVIOUS_VERSION]->(old)")
    fun linkVersions(newId: Long, oldId: Long)

    @Query("MATCH (c:Contact) WHERE id(c) = \$id SET c.deletedAt = \$deletedAt")
    fun setDeletedAt(id: Long, deletedAt: Long)
}
