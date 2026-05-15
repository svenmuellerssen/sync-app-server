package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface ContactRepository : Neo4jRepository<ContactNode, Long> {
    fun findAllByAccountNameAndDeletedAtIsNull(accountName: String): List<ContactNode>
    fun countByAccountNameAndDeletedAtIsNull(accountName: String): Long
    fun findBySyncId(syncId: String): ContactNode?
    @Query("MATCH (c:Contact {lookupKey: \$lookupKey}) RETURN c LIMIT 1")
    fun findByLookupKey(lookupKey: String): ContactNode?

    @Query("MATCH (c:Contact {lookupKey: \$lookupKey, accountName: \$accountName}) RETURN c LIMIT 1")
    fun findByLookupKeyAndAccountName(lookupKey: String, accountName: String): ContactNode?

    @Query("MATCH (c:Contact {accountName: \$accountName}) WHERE c.syncId IN \$syncIds AND c.deletedAt IS NULL RETURN c")
    fun findAllBySyncIdIn(accountName: String, syncIds: List<String>): List<ContactNode>

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

    /**
     * Returns nodes for syncIds that are TRULY tombstoned: has at least one deleted version
     * (deletedAt IS NOT NULL) AND no active version (deletedAt IS NULL) for the same syncId.
     *
     * Correctly distinguishes tombstones from version-history archived nodes, which occur
     * when a contact is updated (old node gets setDeletedAt, new node has deletedAt=null).
     */
    @Query("""
        MATCH (c:Contact {accountName: ${'$'}accountName})
        WHERE c.syncId IN ${'$'}syncIds AND c.deletedAt IS NOT NULL
        AND NOT EXISTS {
            MATCH (active:Contact {accountName: ${'$'}accountName, syncId: c.syncId})
            WHERE active.deletedAt IS NULL
        }
        RETURN DISTINCT c
    """)
    fun findAllTombstonedByAccountNameAndSyncIdIn(accountName: String, syncIds: List<String>): List<ContactNode>
}
