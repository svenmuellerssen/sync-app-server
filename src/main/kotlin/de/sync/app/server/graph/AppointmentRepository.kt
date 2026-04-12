package de.sync.app.server.graph

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

interface AppointmentRepository : Neo4jRepository<AppointmentNode, Long> {
    fun findAllByAccountName(accountName: String): List<AppointmentNode>
    fun countByAccountName(accountName: String): Long
    fun findBySyncId(syncId: String): AppointmentNode?

    @Query("MATCH (a:Appointment)-[:BELONGS_TO_SHARED_CAL]->(sc:SharedCalendar {calendarId: \$calendarId}) RETURN a")
    fun findAllBySharedCalendarId(calendarId: String): List<AppointmentNode>
}