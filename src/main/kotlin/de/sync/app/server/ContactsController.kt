package de.sync.app.server

import de.sync.app.server.graph.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/contacts")
class ContactsController(
    private val contactRepository: ContactRepository,
    private val slotService: SlotService,
) {

    @GetMapping
    fun getContacts(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<ContactListResponse> {
        val accountName = request.getAttribute("accountName") as String
        val contacts = contactRepository.findAllByAccountNameAndDeletedAtIsNull(accountName).map { it.toDto() }
        return ResponseEntity.ok(ContactListResponse(accountName = accountName, contacts = contacts))
    }

    @GetMapping("/count")
    fun getContactCount(
        @RequestHeader("X-Sync-Token") token: String,
        request: HttpServletRequest,
    ): ResponseEntity<ContactCountResponse> {
        val accountName = request.getAttribute("accountName") as String
        val count = contactRepository.countByAccountNameAndDeletedAtIsNull(accountName)
        return ResponseEntity.ok(ContactCountResponse(accountName = accountName, count = count))
    }

    @Transactional
    @PostMapping
    fun uploadContacts(
        @RequestHeader("X-Sync-Token") token: String,
        @RequestBody @jakarta.validation.Valid batch: ContactBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BackupResponse> {
        val accountName = request.getAttribute("accountName") as String
        val now = System.currentTimeMillis()
        var stored = 0

        // Pre-load all existing contacts in two batch queries instead of one query per contact.
        val syncIds = batch.contacts.mapNotNull { it.syncId }
        val lookupKeys = batch.contacts.filter { it.syncId == null }.map { it.lookupKey }

        val bySyncId: Map<String, ContactNode> = if (syncIds.isNotEmpty())
            contactRepository.findAllBySyncIdIn(syncIds).associateBy { it.syncId }
        else emptyMap()

        val byLookupKey: Map<String, ContactNode> = if (lookupKeys.isNotEmpty())
            contactRepository.findAllByAccountNameAndLookupKeyIn(accountName, lookupKeys).associateBy { it.lookupKey }
        else emptyMap()

        for (dto in batch.contacts) {
            val existing = if (dto.syncId != null) bySyncId[dto.syncId] else byLookupKey[dto.lookupKey]
            if (existing != null && existing.lastUpdatedAt >= dto.lastUpdatedAt) continue

            // Save as a new node (no inherited id, fresh versionId) to preserve the old node as history.
            val node = ContactNode(
                id = null,
                versionId = UUID.randomUUID().toString(),
                syncId = dto.syncId ?: existing?.syncId ?: UUID.randomUUID().toString(),
                lookupKey = dto.lookupKey,
                accountName = accountName,
                lastUpdatedAt = dto.lastUpdatedAt,
                createdAt = existing?.createdAt ?: now,
                deletedAt = null,
                displayName = dto.displayName,
                givenName = dto.givenName,
                middleName = dto.middleName,
                familyName = dto.familyName,
                namePrefix = dto.namePrefix,
                nameSuffix = dto.nameSuffix,
                phoneticGivenName = dto.phoneticGivenName,
                phoneticMiddleName = dto.phoneticMiddleName,
                phoneticFamilyName = dto.phoneticFamilyName,
                notes = dto.notes.joinToString("\n").ifEmpty { null },
                phoneNumbers = dto.phoneNumbers.map {
                    PhoneNumberNode(number = it.number, type = it.type, label = it.label)
                }.toMutableList(),
                emailAddresses = dto.emailAddresses.map {
                    EmailNode(address = it.address, type = it.type, label = it.label)
                }.toMutableList(),
                postalAddresses = dto.postalAddresses.map {
                    PostalAddressNode(street = it.street, city = it.city, region = it.region, postCode = it.postCode, country = it.country, type = it.type, label = it.label)
                }.toMutableList(),
                organizations = dto.organizations.map {
                    OrganizationNode(company = it.company, title = it.title, department = it.department)
                }.toMutableList(),
                instantMessengers = dto.instantMessengers.map {
                    InstantMessengerNode(handle = it.handle, protocol = it.protocol, customProtocol = it.customProtocol)
                }.toMutableList(),
            )

            val savedNode = contactRepository.save(node)
            if (existing?.id != null) {
                contactRepository.linkVersions(savedNode.id!!, existing.id)
                contactRepository.setDeletedAt(existing.id, now)
            }
            stored++
        }

        val revision = UUID.randomUUID().toString()
        slotService.invalidateAccount(accountName)
        return ResponseEntity.ok(BackupResponse(revision = revision, contactsStored = stored))
    }
}

data class ContactBatchRequest(
    val accountName: String = "",
    val contacts: List<ContactDtoRequest> = emptyList(),
)

data class ContactDtoRequest(
    val syncId: String? = null,     // UUID — von App generiert; null bei alten App-Versionen
    val lookupKey: String,
    val lastUpdatedAt: Long,
    val displayName: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val familyName: String? = null,
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val phoneticGivenName: String? = null,
    val phoneticMiddleName: String? = null,
    val phoneticFamilyName: String? = null,
    val phoneNumbers: List<PhoneDtoRequest> = emptyList(),
    val emailAddresses: List<EmailDtoRequest> = emptyList(),
    val postalAddresses: List<AddressDtoRequest> = emptyList(),
    val organizations: List<OrgDtoRequest> = emptyList(),
    val instantMessengers: List<ImDtoRequest> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class PhoneDtoRequest(val number: String, val type: Int, val label: String? = null)
data class EmailDtoRequest(val address: String, val type: Int, val label: String? = null)
data class AddressDtoRequest(val street: String? = null, val city: String? = null, val region: String? = null, val postCode: String? = null, val country: String? = null, val type: Int, val label: String? = null)
data class OrgDtoRequest(val company: String? = null, val title: String? = null, val department: String? = null)
data class ImDtoRequest(val handle: String, val protocol: Int, val customProtocol: String? = null)

data class BackupResponse(val revision: String, val contactsStored: Int)
data class ContactCountResponse(val accountName: String, val count: Long)
data class ContactListResponse(val accountName: String, val contacts: List<ContactDtoResponse>)

data class ContactDtoResponse(
    val syncId: String,
    val lookupKey: String,
    val lastUpdatedAt: Long,
    val displayName: String?,
    val givenName: String?,
    val middleName: String?,
    val familyName: String?,
    val namePrefix: String?,
    val nameSuffix: String?,
    val phoneticGivenName: String?,
    val phoneticMiddleName: String?,
    val phoneticFamilyName: String?,
    val phoneNumbers: List<PhoneDtoRequest>,
    val emailAddresses: List<EmailDtoRequest>,
    val postalAddresses: List<AddressDtoRequest>,
    val organizations: List<OrgDtoRequest>,
    val instantMessengers: List<ImDtoRequest>,
    val notes: List<String>,
)

internal fun ContactNode.toDto() = ContactDtoResponse(
    syncId = syncId,
    lookupKey = lookupKey,
    lastUpdatedAt = lastUpdatedAt,
    displayName = displayName,
    givenName = givenName,
    middleName = middleName,
    familyName = familyName,
    namePrefix = namePrefix,
    nameSuffix = nameSuffix,
    phoneticGivenName = phoneticGivenName,
    phoneticMiddleName = phoneticMiddleName,
    phoneticFamilyName = phoneticFamilyName,
    phoneNumbers = phoneNumbers.map { PhoneDtoRequest(it.number, it.type, it.label) },
    emailAddresses = emailAddresses.map { EmailDtoRequest(it.address, it.type, it.label) },
    postalAddresses = postalAddresses.map { AddressDtoRequest(it.street, it.city, it.region, it.postCode, it.country, it.type, it.label) },
    organizations = organizations.map { OrgDtoRequest(it.company, it.title, it.department) },
    instantMessengers = instantMessengers.map { ImDtoRequest(it.handle, it.protocol, it.customProtocol) },
    notes = notes?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList(),
)

