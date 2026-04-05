package de.sync.app.server.data

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ContactRepository : JpaRepository<ContactEntity, String> {

    fun countByAccountName(accountName: String): Long
}
