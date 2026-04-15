package de.sync.app.server

import de.sync.app.server.dto.AppointmentManifest
import de.sync.app.server.dto.ContactManifest
import de.sync.app.server.dto.ManifestRequest
import de.sync.app.server.dto.ManifestResponse
import de.sync.app.server.graph.AppointmentRepository
import de.sync.app.server.graph.ContactRepository
import de.sync.app.server.graph.SharedCalendarRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private data class DedupKey(val title: String, val dtStart: Long, val dtEnd: Long?, val rrule: String?)

@RestController
@RequestMapping("/sync")
class SyncController(
    private val contactRepository: ContactRepository,
    private val appointmentRepository: AppointmentRepository,
    private val sharedCalendarRepository: SharedCalendarRepository,
) {

    @Transactional
    @PostMapping("/manifest")
    fun manifest(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody request: ManifestRequest,
        req: HttpServletRequest,
    ): ResponseEntity<ManifestResponse> {
        val accountName = req.getAttribute("accountName") as String
        val emptyContacts = ContactManifest(emptyList(), emptyList(), emptyList())
        val emptyAppointments = AppointmentManifest(emptyList(), emptyList(), emptyList())

        val contactManifest = if (request.type == "contacts" || request.type == "all")
            buildContactManifest(request, accountName) else emptyContacts
        val appointmentManifest = if (request.type == "appointments" || request.type == "all")
            buildAppointmentManifest(request, accountName) else emptyAppointments

        return ResponseEntity.ok(ManifestResponse(contactManifest, appointmentManifest))
    }

    private fun buildContactManifest(request: ManifestRequest, accountName: String): ContactManifest {
        val localMap = request.contacts.associateBy { it.syncId }
        val serverNodes = contactRepository.findAllByAccountName(accountName)
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

    private fun buildAppointmentManifest(request: ManifestRequest, accountName: String): AppointmentManifest {
        val localMap = request.appointments.associateBy { it.syncId }
        val serverNodes = appointmentRepository.findAllByAccountName(accountName)
        val serverMap = serverNodes.associateBy { it.syncId }

        // Phone is source of truth for this account's own appointments.
        // Delete anything on the server that the phone no longer has.
        val missingOnPhone = serverNodes.filter { it.syncId !in localMap }
        missingOnPhone.forEach { node -> appointmentRepository.deleteById(node.id!!) }

        // Deduplicate: keep oldest entry (smallest createdAt) per (title, dtStart, dtEnd, rrule)
        val surviving = serverNodes.filter { it.syncId in localMap }
        surviving.groupBy { DedupKey(it.title, it.dtStart, it.dtEnd, it.rrule) }.forEach { (_, group) ->
            if (group.size > 1) {
                val oldest = group.minByOrNull { it.createdAt }!!
                group.filter { it.id != oldest.id }.forEach { appointmentRepository.deleteById(it.id!!) }
            }
        }

        val toUpload = (localMap.keys - serverMap.keys).toList()

        // SharedCalendar events from other members: download to this phone if missing
        val sharedCalendars = sharedCalendarRepository.findAllByMemberAccountName(accountName)
        val sharedCalendarAppointments = sharedCalendars.flatMap { sc ->
            appointmentRepository.findAllBySharedCalendarId(sc.calendarId)
        }
        val sharedToDownload = sharedCalendarAppointments
            .filter { it.syncId !in localMap && it.accountName != accountName }
            .map { it.toDto() }
        val sharedToUpdate = sharedCalendarAppointments.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.accountName != accountName && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        return AppointmentManifest(
            toUpload = toUpload,
            toDownload = sharedToDownload.toMutableList(),
            toUpdate = sharedToUpdate.toMutableList(),
        )
    }
}