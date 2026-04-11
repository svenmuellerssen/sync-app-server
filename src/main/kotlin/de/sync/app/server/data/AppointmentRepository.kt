package de.sync.app.server.data

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface AppointmentRepository : JpaRepository<AppointmentEntity, String> {

    // Lädt Attendees in einer SQL-Query via LEFT JOIN (kein N+1-Problem)
    @EntityGraph(attributePaths = ["attendees"])
    fun findAllByAccountName(accountName: String): List<AppointmentEntity>

    fun countByAccountName(accountName: String): Long

    fun findAllByAccountNameAndDeviceId(accountName: String, deviceId: Long): List<AppointmentEntity>
}
