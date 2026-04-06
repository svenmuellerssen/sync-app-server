package de.sync.app.server.data

import org.springframework.data.jpa.repository.JpaRepository

interface AppointmentRepository : JpaRepository<AppointmentEntity, String> {
    fun findAllByAccountName(accountName: String): List<AppointmentEntity>
    fun countByAccountName(accountName: String): Long
    fun findByAccountNameAndDeviceId(accountName: String, deviceId: Long): AppointmentEntity?
}

