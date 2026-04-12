package de.sync.app.server

import de.sync.app.server.cache.SessionRepository
import de.sync.app.server.dto.AppointmentManifest
import de.sync.app.server.dto.ContactManifest
import de.sync.app.server.dto.ManifestRequest
import de.sync.app.server.dto.ManifestResponse
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarRepository
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/sync")
class SyncController(
    private val contactRepository: ContactRepository,
    private val appointmentRepository: AppointmentRepository,
    private val sessionRepository: SessionRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
) {

    @Transactional
    @PostMapping("/manifest")
    fun manifest(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody request: ManifestRequest,
    ): ResponseEntity<ManifestResponse> {
        if (!sessionRepository.findById(token).isPresent) {
            return ResponseEntity.status(401).build()
        }

        val contactManifest = buildContactManifest(request)
        val appointmentManifest = buildAppointmentManifest(request)

        return ResponseEntity.ok(ManifestResponse(contactManifest, appointmentManifest))
    }

    private fun buildContactManifest(request: ManifestRequest): ContactManifest {
        val localMap = request.contacts.associateBy { it.syncId }
        val serverNodes = contactRepository.findAllByAccountName(request.accountName)
        val serverMap = serverNodes.associateBy { it.syncId }

        val missingOnPhone = serverMap.keys - localMap.keys
        val toDownload = missingOnPhone.mapNotNull { serverMap[it]?.toDto() }
        val toUpload = (localMap.keys - serverMap.keys).toList()
        val toUpdate = serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        return ContactManifest(toUpload = toUpload, toDownload = toDownload, toUpdate = toUpdate)
    }

    private fun buildAppointmentManifest(request: ManifestRequest): AppointmentManifest {
        val localMap = request.appointments.associateBy { it.syncId }
        val serverNodes = appointmentRepository.findAllByAccountName(request.accountName)
        val serverMap = serverNodes.associateBy { it.syncId }

        val missingOnPhone = serverNodes.filter { it.syncId !in localMap }
        missingOnPhone.forEach { node ->
            if (node.calendarAccountType != null && node.calendarAccountType != "LOCAL"
                && node.calendarAccountType != "de.sync.contacts") {
                appointmentRepository.deleteById(node.id!!)
            }
        }

        val toDownload = missingOnPhone
            .filter { it.calendarAccountType == null || it.calendarAccountType == "LOCAL" }
            .map { it.toDto() }
            .toMutableList()

        val toUpdate = serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null &&
                node.lastUpdatedAt > local.lastUpdatedAt &&
                (node.calendarAccountType == null || node.calendarAccountType == "LOCAL")
        }.map { it.toDto() }.toMutableList()

        // SharedCalendar events: include events from all calendars where this account is a member
        val sharedCalendars = sharedCalendarRepository.findAllByMemberAccountName(request.accountName)
        val sharedCalendarAppointments = sharedCalendars.flatMap { sc ->
            appointmentRepository.findAllBySharedCalendarId(sc.calendarId)
        }
        val sharedToDownload = sharedCalendarAppointments
            .filter { it.syncId !in localMap }
            .map { it.toDto() }
        val sharedToUpdate = sharedCalendarAppointments.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        toDownload.addAll(sharedToDownload)
        toUpdate.addAll(sharedToUpdate)

        val toUpload = (localMap.keys - serverMap.keys).toList()

        return AppointmentManifest(toUpload = toUpload, toDownload = toDownload, toUpdate = toUpdate)
    }
}