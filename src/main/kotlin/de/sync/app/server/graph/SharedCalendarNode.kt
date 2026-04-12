package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("SharedCalendar")
class SharedCalendarNode(
    @Id @GeneratedValue val id: Long? = null,
    val calendarId: String,
    val name: String,
    val color: Int = 0xFF4CAF50.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val createdBy: String,

    @Relationship(type = "HAS_MEMBER", direction = OUTGOING)
    val members: MutableList<AccountNode> = mutableListOf(),
)
