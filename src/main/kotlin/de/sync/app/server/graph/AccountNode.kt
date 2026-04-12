package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node

@Node("Account")
data class AccountNode(
    @Id @GeneratedValue val id: Long? = null,
    val username: String,
    val passwordHash: String,
    val createdAt: Long = System.currentTimeMillis(),
)
