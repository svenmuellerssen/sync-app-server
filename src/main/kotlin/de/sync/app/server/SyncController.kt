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
    private val slotService: SlotService,
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
        val serverNodes = contactRepository.findAllByAccountNameAndDeletedAtIsNull(accountName)
        val serverMap = serverNodes.associateBy { it.syncId }

        val missingOnPhone = serverMap.keys - localMap.keys
        val toDownload = missingOnPhone.mapNotNull { serverMap[it]?.toDto() }
        // Include contacts missing from server + contacts where phone version is newer than server.
        val toUpload = ((localMap.keys - serverMap.keys) + serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && local.lastUpdatedAt > node.lastUpdatedAt
        }.map { it.syncId }).toList()
        val toUpdate = serverNodes.filter { node ->
            val local = localMap[node.syncId]
            local != null && node.lastUpdatedAt > local.lastUpdatedAt
        }.map { it.toDto() }

        return ContactManifest(toUpload = toUpload, toDownload = toDownload, toUpdate = toUpdate)
    }

    private fun buildAppointmentManifest(request: ManifestRequest, accountName: String): AppointmentManifest {
        val localMap = request.appointments.associateBy { it.syncId }

        // Include own shared-calendar appointments so the manifest sees them as "already on server"
        // and doesn't put them in toUpload every sync (Bug 2 fix).
        val personalNodes = appointmentRepository.findAllCurrentByAccountName(accountName)
        val ownSharedNodes = appointmentRepository.findAllCurrentSharedByAccountName(accountName)
        val serverNodes = personalNodes + ownSharedNodes
        val serverMap = serverNodes.associateBy { it.syncId }

        // G1 fix: only soft-archive when the phone actually confirmed a successful read.
        // If the phone sends an empty list without confirmedEmpty=true, it could be a read
        // error or permission denial — we must not soft-archive all server appointments.
        val canSoftArchive = localMap.isNotEmpty() || request.confirmedEmpty
        val now = System.currentTimeMillis()
        if (canSoftArchive) {
            val missingOnPhone = serverNodes.filter { it.syncId !in localMap }
            missingOnPhone.forEach { node -> appointmentRepository.softArchiveById(node.id!!, now) }
        }

        // G3 fix: dedup by content-key, soft-archive losers (preserve history) instead of hard-delete.
        // Keeps the oldest node (smallest createdAt) as the canonical version.
        val surviving = serverNodes.filter { it.syncId in localMap }
        surviving.groupBy { DedupKey(it.title, it.dtStart, it.dtEnd, it.rrule) }.forEach { (_, group) ->
            if (group.size > 1) {
                val oldest = group.minByOrNull { it.createdAt }!!
                group.filter { it.id != oldest.id }.forEach {
                    appointmentRepository.softArchiveById(it.id!!, now)
                }
            }
        }

        // Invalidate slot cache if any appointments were soft-archived or deduped (G6 fix).
        if (canSoftArchive) {
            slotService.invalidateAccount(accountName)
        }

        val toUpload = (localMap.keys - serverMap.keys).toList()

        // G4/G5 fix: shared calendar appointments from OTHER members — download to this phone.
        // findAllCurrentByAccountName (used above) already returns only LOCAL cal appointments,
        // so personal appointments never leak into sharedCalendarAppointments (G5).
        // The accountName filter below handles G4 (own shared-cal uploads never re-downloaded).
        val sharedCalendars = sharedCalendarRepository.findAllAccessibleByAccountName(accountName)
        val calendarIds = sharedCalendars.map { it.calendarId }
        val sharedCalendarAppointments = if (calendarIds.isEmpty()) emptyList()
            else appointmentRepository.findAllCurrentBySharedCalendarIds(calendarIds)
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
