package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository

interface GoogleCalendarRepository : Neo4jRepository<GoogleCalendarNode, Long> {
    fun findByCalendarIdAndAccountName(calendarId: String, accountName: String): GoogleCalendarNode?
    fun findAllByAccountName(accountName: String): List<GoogleCalendarNode>
}
