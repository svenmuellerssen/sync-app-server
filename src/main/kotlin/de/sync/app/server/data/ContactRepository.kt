package de.sync.app.server.data

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ContactRepository : JpaRepository<ContactEntity, Long> {

    fun countByAccountName(accountName: String): Long

    // Lädt alle Collections in einer SQL-Query via LEFT JOIN (kein N+1-Problem)
    @EntityGraph(attributePaths = ["phoneNumbers", "emailAddresses", "postalAddresses", "organizations", "instantMessengers"])
    fun findAllByAccountName(accountName: String): List<ContactEntity>

    fun findByLookupKey(lookupKey: String): ContactEntity?

    fun findByAccountNameAndLookupKey(accountName: String, lookupKey: String): ContactEntity?
}
