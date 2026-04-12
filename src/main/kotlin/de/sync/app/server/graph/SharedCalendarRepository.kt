package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface SharedCalendarRepository : Neo4jRepository<SharedCalendarNode, Long> {
    fun findByCalendarId(calendarId: String): SharedCalendarNode?

    @Query("MATCH (sc:SharedCalendar)-[:HAS_MEMBER]->(a:Account {username: \$accountName}) RETURN sc")
    fun findAllByMemberAccountName(accountName: String): List<SharedCalendarNode>
}
