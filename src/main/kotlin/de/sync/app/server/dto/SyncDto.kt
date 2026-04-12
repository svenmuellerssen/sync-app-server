package de.sync.app.server.dto

import de.sync.app.server.AppointmentDtoResponse
import de.sync.app.server.ContactDtoResponse

// --- Request ---

data class ManifestRequest(
    val accountName: String,
    val contacts: List<SyncEntry> = emptyList(),
    val appointments: List<SyncEntry> = emptyList(),
)

/** Eine einzelne Zeile im Manifest: syncId + Timestamp des lokalen Eintrags. */
data class SyncEntry(
    val syncId: String,
    val lastUpdatedAt: Long,
)

// --- Response ---

data class ManifestResponse(
    val contacts: ContactManifest,
    val appointments: AppointmentManifest,
)

data class ContactManifest(
    /** syncIds, die das Telefon hochladen soll (fehlen auf dem Server) */
    val toUpload: List<String>,
    /** Kontakte, die das Telefon lokal anlegen soll (fehlen auf dem Telefon) */
    val toDownload: List<ContactDtoResponse>,
    /** Kontakte, bei denen der Server neuer ist → Telefon soll updaten */
    val toUpdate: List<ContactDtoResponse>,
)

data class AppointmentManifest(
    /** syncIds, die das Telefon hochladen soll (fehlen auf dem Server) */
    val toUpload: List<String>,
    /** LOCAL-Kalender-Termine, die das Telefon lokal anlegen soll */
    val toDownload: List<AppointmentDtoResponse>,
    /** LOCAL-Kalender-Termine, bei denen der Server neuer ist */
    val toUpdate: List<AppointmentDtoResponse>,
)
