package de.sync.app.server.data

import jakarta.persistence.*

@Entity
@Table(name = "contacts")
data class ContactEntity(

    @Id
    @Column(name = "lookup_key", nullable = false)
    val lookupKey: String,

    @Column(name = "account_name", nullable = false)
    val accountName: String,

    @Column(name = "last_updated_at", nullable = false)
    val lastUpdatedAt: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long,

    @Column(name = "display_name")
    val displayName: String? = null,

    @Column(name = "given_name")
    val givenName: String? = null,

    @Column(name = "middle_name")
    val middleName: String? = null,

    @Column(name = "family_name")
    val familyName: String? = null,

    @Column(name = "name_prefix")
    val namePrefix: String? = null,

    @Column(name = "name_suffix")
    val nameSuffix: String? = null,

    @Column(name = "phonetic_given_name")
    val phoneticGivenName: String? = null,

    @Column(name = "phonetic_middle_name")
    val phoneticMiddleName: String? = null,

    @Column(name = "phonetic_family_name")
    val phoneticFamilyName: String? = null,

    @Column(name = "notes", columnDefinition = "TEXT")
    val notes: String? = null,

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "lookup_key")
    val phoneNumbers: MutableList<ContactPhoneEntity> = mutableListOf(),

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "lookup_key")
    val emailAddresses: MutableList<ContactEmailEntity> = mutableListOf(),

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "lookup_key")
    val postalAddresses: MutableList<ContactAddressEntity> = mutableListOf(),

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "lookup_key")
    val organizations: MutableList<ContactOrganizationEntity> = mutableListOf(),

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "lookup_key")
    val instantMessengers: MutableList<ContactImEntity> = mutableListOf(),
)

@Entity
@Table(name = "contact_phones")
data class ContactPhoneEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "lookup_key", nullable = false) val lookupKey: String,
    val number: String,
    val type: Int,
    val label: String? = null,
)

@Entity
@Table(name = "contact_emails")
data class ContactEmailEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "lookup_key", nullable = false) val lookupKey: String,
    val address: String,
    val type: Int,
    val label: String? = null,
)

@Entity
@Table(name = "contact_addresses")
data class ContactAddressEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "lookup_key", nullable = false) val lookupKey: String,
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    @Column(name = "post_code") val postCode: String? = null,
    val country: String? = null,
    val type: Int,
    val label: String? = null,
)

@Entity
@Table(name = "contact_organizations")
data class ContactOrganizationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "lookup_key", nullable = false) val lookupKey: String,
    val company: String? = null,
    val title: String? = null,
    val department: String? = null,
)

@Entity
@Table(name = "contact_instant_messengers")
data class ContactImEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "lookup_key", nullable = false) val lookupKey: String,
    val handle: String,
    val protocol: Int,
    @Column(name = "custom_protocol") val customProtocol: String? = null,
)
