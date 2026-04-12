package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("GoogleCalendar")
class GoogleCalendarNode(
    @Id @GeneratedValue val id: Long? = null,
    val calendarId: String,
    val displayName: String,
    val calendarAccountName: String,
    val color: Int? = null,
    val accessLevel: Int? = null,
    val accountName: String,

    @Relationship(type = "HAS_MEMBER", direction = OUTGOING)
    val members: MutableList<ContactNode> = mutableListOf(),
)
