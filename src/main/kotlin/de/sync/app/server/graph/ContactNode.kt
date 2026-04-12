package de.sync.app.server.graph

import org.springframework.data.neo4j.core.schema.GeneratedValue
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING

@Node("Contact")
class ContactNode(
    @Id @GeneratedValue val id: Long? = null,

    val syncId: String,         // UUID — von App generiert, stabiler Upsert-Schlüssel
    val lookupKey: String,      // Android LOOKUP_KEY — bleibt für interne Referenzierung
    val accountName: String,

    val lastUpdatedAt: Long,
    val createdAt: Long,

    val displayName: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val familyName: String? = null,
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val phoneticGivenName: String? = null,
    val phoneticMiddleName: String? = null,
    val phoneticFamilyName: String? = null,
    val notes: String? = null,

    @Relationship(type = "HAS_PHONE", direction = OUTGOING)
    val phoneNumbers: MutableList<PhoneNumberNode> = mutableListOf(),

    @Relationship(type = "HAS_EMAIL", direction = OUTGOING)
    val emailAddresses: MutableList<EmailNode> = mutableListOf(),

    @Relationship(type = "HAS_ADDRESS", direction = OUTGOING)
    val postalAddresses: MutableList<PostalAddressNode> = mutableListOf(),

    @Relationship(type = "HAS_ORG", direction = OUTGOING)
    val organizations: MutableList<OrganizationNode> = mutableListOf(),

    @Relationship(type = "HAS_IM", direction = OUTGOING)
    val instantMessengers: MutableList<InstantMessengerNode> = mutableListOf(),
) {
    override fun equals(other: Any?) = other is ContactNode && syncId == other.syncId
    override fun hashCode() = syncId.hashCode()
}

@Node("PhoneNumber")
data class PhoneNumberNode(
    @Id @GeneratedValue val id: Long? = null,
    val number: String,
    val type: Int,
    val label: String? = null,
)

@Node("Email")
data class EmailNode(
    @Id @GeneratedValue val id: Long? = null,
    val address: String,
    val type: Int,
    val label: String? = null,
)

@Node("PostalAddress")
data class PostalAddressNode(
    @Id @GeneratedValue val id: Long? = null,
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postCode: String? = null,
    val country: String? = null,
    val type: Int,
    val label: String? = null,
)

@Node("Organization")
data class OrganizationNode(
    @Id @GeneratedValue val id: Long? = null,
    val company: String? = null,
    val title: String? = null,
    val department: String? = null,
)

@Node("InstantMessenger")
data class InstantMessengerNode(
    @Id @GeneratedValue val id: Long? = null,
    val handle: String,
    val protocol: Int,
    val customProtocol: String? = null,
)
