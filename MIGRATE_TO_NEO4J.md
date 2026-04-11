# Migration: MySQL → Neo4j

Dieses Dokument beschreibt alle Schritte, um Kontakte, Termine, Bookings und Messages
von MySQL/JPA auf Neo4j (Spring Data Neo4j) umzustellen — bei vollständiger Erhaltung
aller bestehenden HTTP-Endpunkte, Bedingungen und Geschäftslogik.

---

## Entscheidung: Sessions → Redis

Sessions haben keinen Graph-Charakter und sind ein klassischer Key-Value-Anwendungsfall.
**Sessions werden in Redis gespeichert — MySQL entfällt damit vollständig.**

| Store  | Enthält |
|--------|---------|
| Neo4j  | Contact, Appointment, Booking, BookingMessage |
| Redis  | Session + SlotService-Cache |
| MySQL  | **entfällt** |

### Warum Redis ideal für Sessions ist

| Eigenschaft | Session | Redis |
|---|---|---|
| Zugriff | immer per Token (Key-Lookup) | native Key-Value-Operation — O(1) |
| Ablauf | 24h TTL | **nativer TTL-Support** — Redis löscht abgelaufene Keys automatisch |
| `deleteExpired()` | manueller `@Modifying`-Query nötig | **entfällt komplett** |
| Struktur | kein Graph, keine Joins | passt — einfaches Objekt |
| Persistenz | muss Server-Restart überleben | Redis mit `--save` (bereits so konfiguriert) |

### SessionEntity (cache/SessionEntity.kt)

```kotlin
// VORHER — MySQL/JPA
@Entity @Table(name = "sessions")
data class SessionEntity(
    @Id val token: String,
    val accountName: String,
    val expiresAt: Long,
    val createdAt: Long,
)

// NACHHER — Redis
@RedisHash("session")
data class SessionEntity(
    @Id val token: String,
    val accountName: String,
    @TimeToLive val ttlSeconds: Long = 86400,  // 24h — Redis löscht automatisch
    val createdAt: Long,
)
```

### SessionRepository (cache/SessionRepository.kt)

```kotlin
// VORHER — JpaRepository mit manuellem deleteExpired()
interface SessionRepository : JpaRepository<SessionEntity, String> {
    @Modifying
    @Query("DELETE FROM sessions WHERE expires_at < :nowMs")
    fun deleteExpired(nowMs: Long)
}

// NACHHER — CrudRepository, deleteExpired() entfällt vollständig
interface SessionRepository : CrudRepository<SessionEntity, String>
// findById(token) — identisch nutzbar im AuthController
// save(session)   — setzt TTL automatisch über @TimeToLive
```

---

## 0. Migrationsfahrplan

Die Migration ist in 6 Phasen unterteilt. **Jede Phase setzt die vorherige voraus.**
Der Server bleibt während der Migration auf MySQL laufen — es gibt eine kurze Umstellungsphase (Schritt 5).

```
Phase 0 — Infrastruktur
  └─> Phase 1 — Server: Auth + Graph-Nodes (Fundament)
        └─> Phase 2 — Server: Repositories + Controller
              └─> Phase 3 — Server: Bidirektionaler Sync
                    └─> Phase 4 — App: Sync-Flow + Checkbox
                          └─> Phase 5 — Datenmigration + MySQL entfernen
                                └─> Phase 6 — Familienkalender (SharedCalendar)
```

---

### Phase 0 — Infrastruktur prüfen

**Voraussetzung für alles weitere. Kein Code.**

| Schritt | Aufgabe | Abhängigkeit |
|---------|---------|-------------|
| 0.1 | Neo4j-Container in `cluster-nas.bat` läuft + erreichbar | — |
| 0.2 | Redis-Container in `cluster-nas.bat` läuft + erreichbar | — |
| 0.3 | MySQL-Container läuft noch (bleibt bis Phase 5) | — |
| 0.4 | Python + `mysql-connector-python` + `neo4j`-Package auf NAS verfügbar | — |

---

### Phase 1 — Server: Auth + Graph-Nodes (Fundament)

**AccountNode muss zuerst existieren**, weil alle anderen Nodes `accountName` referenzieren
und Phase 2 (SharedCalendar) darauf aufbaut. Redis-Session-Migration ist unabhängig,
aber logisch hier einzuordnen.

| Schritt | Aufgabe | Datei | Abhängigkeit |
|---------|---------|-------|-------------|
| 1.1 | `AccountNode` anlegen (username, passwordHash) | `graph/AccountNode.kt` | — |
| 1.2 | `AccountRepository` anlegen | `graph/AccountRepository.kt` | 1.1 |
| 1.3 | `POST /auth/register` im `AuthController` | `AuthController.kt` | 1.2 |
| 1.4 | `POST /auth/login` — Passwort-Check einbauen | `AuthController.kt` | 1.2 |
| 1.5 | `SessionEntity` → `@RedisHash` + `@TimeToLive` | `cache/SessionEntity.kt` | Redis (0.2) |
| 1.6 | `SessionRepository` → `CrudRepository` | `cache/SessionRepository.kt` | 1.5 |
| 1.7 | `AuthController` — `deleteExpired()` entfernen | `AuthController.kt` | 1.6 |
| 1.8 | `ContactNode` + 5 Child-Nodes + `syncId` | `graph/ContactNode.kt` | 1.1 |
| 1.9 | `AppointmentNode` + `AttendeeNode` + `MessageNode` + `syncId` | `graph/AppointmentNode.kt` | 1.1, 1.8 (für IS_CONTACT) |

---

### Phase 2 — Server: Repositories + Controller

Setzt voraus, dass alle Graph-Nodes aus Phase 1 definiert sind.
**JPA-Code bleibt bis Phase 5 parallel bestehen** — Controller werden umgeschrieben, nicht gelöscht.

| Schritt | Aufgabe | Datei | Abhängigkeit |
|---------|---------|-------|-------------|
| 2.1 | `ContactRepository` (Neo4j) + `findByAccountNameAndEmail` | `graph/ContactRepository.kt` | 1.8 |
| 2.2 | `AppointmentRepository` (Neo4j) + `findBySyncId` | `graph/AppointmentRepository.kt` | 1.9 |
| 2.3 | `ContactsController` auf Neo4j umschreiben | `ContactsController.kt` | 2.1 |
| 2.4 | `AppointmentsController` auf Neo4j umschreiben | `AppointmentsController.kt` | 2.2 |
| 2.5 | `DataConfig.kt` — `@EnableJpaRepositories` entfernen | `DataConfig.kt` | 2.3, 2.4 |
| 2.6 | `build.gradle.kts` — JPA/MySQL-Dependencies entfernen | `build.gradle.kts` | 2.5 |
| 2.7 | `application.properties` — `spring.datasource.*` + `spring.jpa.*` entfernen | `application.properties` | 2.6 |

> ⚠️ Nach Schritt 2.5 läuft der Server **nur noch mit Neo4j** — MySQL-Container kann noch laufen,
> wird aber nicht mehr angesprochen. Datenmigration muss **vor** 2.5 abgeschlossen sein (→ Phase 5).

---

### Phase 3 — Server: Bidirektionaler Sync

Setzt voraus, dass `ContactRepository` + `AppointmentRepository` auf Neo4j laufen.

| Schritt | Aufgabe | Datei | Abhängigkeit |
|---------|---------|-------|-------------|
| 3.1 | `ManifestRequest` + `ManifestResponse` DTOs definieren | `dto/SyncDto.kt` | 2.1, 2.2 |
| 3.2 | `POST /sync/manifest` — Server-Logik (toUpload / toDownload / toUpdate) | `SyncController.kt` | 3.1 |

---

### Phase 4 — App: Sync-Flow + Checkbox

Setzt voraus, dass Server-Endpunkte aus Phase 1–3 deployed sind.

| Schritt | Aufgabe | Datei | Abhängigkeit |
|---------|---------|-------|-------------|
| 4.1 | Checkbox "Neues Konto anlegen" in `AddAccountActivity` | `ui/AddAccountActivity.kt` | 1.3, 1.4 |
| 4.2 | `ContactWriter` — schreibt `toDownload`/`toUpdate` in `ContactsContract` | `sync/ContactWriter.kt` | 3.2 |
| 4.3 | `CalendarWriter` — schreibt `toDownload`/`toUpdate` in `CalendarContract` | `sync/CalendarWriter.kt` | 3.2 |
| 4.4 | `ContactSyncWorker` — Manifest-Flow integrieren | `sync/ContactSyncWorker.kt` | 4.2 |
| 4.5 | `CalendarSyncWorker` — Manifest-Flow integrieren | `sync/CalendarSyncWorker.kt` | 4.3 |

---

### Phase 5 — Datenmigration + MySQL entfernen

**Muss vor Phase 2 Schritt 2.5 ausgeführt werden** (solange MySQL noch läuft).

| Schritt | Aufgabe | Abhängigkeit |
|---------|---------|-------------|
| 5.1 | `migration/migrate_to_neo4j.py` auf NAS übertragen | Phase 1 abgeschlossen (Nodes definiert) |
| 5.2 | Skript ausführen — Kontakte + Termine von MySQL → Neo4j migrieren | 5.1, MySQL + Neo4j laufen |
| 5.3 | Ergebnis verifizieren: Anzahl Nodes in Neo4j == Anzahl Rows in MySQL | 5.2 |
| 5.4 | Server neu deployen (Phase 2 Schritt 2.5–2.7) | 5.3 |
| 5.5 | MySQL-Container aus `cluster-nas.bat` entfernen + stoppen | 5.4 |

---

### Phase 6 — Familienkalender (SharedCalendar) — nach stabilem Sync

Setzt voraus, dass Phase 1–5 abgeschlossen und der bidirektionale Sync fehlerfrei läuft.

| Schritt | Aufgabe | Datei | Abhängigkeit |
|---------|---------|-------|-------------|
| 6.1 | `SharedCalendarNode` + `[:HAS_MEMBER]` + `[:BELONGS_TO]` | `graph/SharedCalendarNode.kt` | 1.1 (AccountNode) |
| 6.2 | `SharedCalendarRepository` | `graph/SharedCalendarRepository.kt` | 6.1 |
| 6.3 | `/calendar`-Endpunkte (CRUD + Invite-Code) | `SharedCalendarController.kt` | 6.2 |
| 6.4 | Invite-Code in Redis (TTL 10 Min, einmalig nutzbar) | `SharedCalendarController.kt` | 6.3, Redis |
| 6.5 | `POST /sync/manifest` — geteilte Termine in `toDownload` einbeziehen | `SyncController.kt` | 6.2, 3.2 |
| 6.6 | App: SharedCalendar-UI (Kalender anlegen, Einladung annehmen) | TBD | 6.3 |

---

**Entscheidung: Appointment und Booking sind dieselbe Node-Klasse.**
Kein `source`-Property, kein `deviceId`, keine `invitees` — nur `Attendees` für alles.
Der Unterschied zwischen phone-importierten Terminen und Bookings ergibt sich implizit:
phone-Termine haben keine Messages, Bookings können Messages haben.

```
(:Contact {lookupKey, accountName, displayName, givenName, familyName,
           middleName, namePrefix, nameSuffix, phoneticGivenName,
           phoneticMiddleName, phoneticFamilyName, notes,
           lastUpdatedAt, createdAt})
  -[:HAS_PHONE]->     (:PhoneNumber   {number, type, label})
  -[:HAS_EMAIL]->     (:Email         {address, type, label})
  -[:HAS_ADDRESS]->   (:PostalAddress {street, city, region, postCode, country, type, label})
  -[:HAS_ORG]->       (:Organization  {company, title, department})
  -[:HAS_IM]->        (:InstantMessenger {handle, protocol, customProtocol})

(:Appointment {id, accountName, title, description, dtStart, dtEnd,
               duration, allDay, timezone, rrule, location, organizer,
               calendarName, calendarAccountType, calendarAccountName,
               calendarColor,                          ← NEU: für Restore auf neuem Gerät
               lastUpdatedAt, createdAt})
  -[:HAS_ATTENDEE]->  (:Attendee {name, email, status})
                           |
                     [:IS_CONTACT]     ← optional, nur wenn Attendee einem bekannten Kontakt entspricht
                           ↓
                       (:Contact)
  -[:HAS_MESSAGE]->   (:Message {id, senderName, text, createdAt})
                           ← nur bei Bookings befüllt, phone-Termine haben keine Messages

(:Session {token, accountName, ttlSeconds, createdAt})   ← Redis (@RedisHash)
```

### Zwei APIs — ein Datenmodell

| API | Zweck | Erstellt |
|-----|-------|---------|
| `POST /appointment` | Phone → Server: Kalender-Import (Backup) | `(:Appointment)` per Upsert |
| `GET /appointment` | Server → Phone: Restore (alle Termine des Accounts inkl. Bookings) | — |
| `GET /booking/slots/available` | Freie Zeitslots abfragen (prüft Appointments im Zeitraum) | — |
| `POST /booking` | Slot buchen → legt Appointment an | `(:Appointment)` |
| `GET /booking/:id/chat` | Chat eines Bookings lesen | — |
| `POST /booking/:id/message` | Nachricht zu Booking schreiben | `(:Message)` |

### Upsert-Strategie (phone-Import)

`POST /appointment` → Server sucht per `accountName` + stabiler ID (z.B. Kombination aus `dtStart` + `title` oder eine vom Phone mitgelieferte UUID) → löscht bestehenden Eintrag → legt neu an. Kein `deviceId` — die Android-lokale Kalender-ID ist nicht stabil und wird nicht gespeichert.

### Restore-Strategie

`GET /appointment` liefert **alle** `(:Appointment)`-Nodes des Accounts — inklusive Bookings (gewünscht).

**Unterscheidung nach Kalendertyp:**

| `calendarAccountType` | Restore auf Gerät | Grund |
|-----------------------|-------------------|-------|
| `LOCAL` | ✅ Kalender anlegen + Termine einfügen | Nur auf Gerät, kein Cloud-Backup |
| `com.google`, `com.exchange` etc. | ❌ überspringen | Google/Exchange synct automatisch |
| Bookings (kein Kalender) | ✅ in lokalen Sync-Kalender schreiben | Server-seitig angelegt |

**Kalender-Restore-Logik (App, nur für LOCAL):**

```
1. Alle LOCAL-Appointments aus toDownload gruppieren nach calendarName
2. Für jede Gruppe:
   a. Suche Kalender auf Gerät per calendarName (CalendarContract.Calendars)
   b. Gefunden → calendarId verwenden
   c. Nicht gefunden → neuen Kalender anlegen:
        ACCOUNT_TYPE = "LOCAL"
        NAME = calendarName
        CALENDAR_DISPLAY_NAME = calendarName
        CALENDAR_COLOR = calendarColor  ← gespeicherte Farbe vom alten Gerät
3. Termine in den gefundenen/neuen Kalender einfügen
```

### Multi-Gerät (Option A — aktuelle Entscheidung)

Derselbe `accountName` wird auf allen Geräten beim Setup eingetragen. Alle Kontakte und Termine landen automatisch im selben Bucket. Kein Mehraufwand serverseitig.

### Multi-Gerät (Option B — für spätere Erweiterung dokumentiert)

Ein `(:Person)`-Node verknüpft mehrere Accounts:

```
(:Person {id, name})
  -[:HAS_ACCOUNT]-> (:Account {accountName: "hans-pixel"})
  -[:HAS_ACCOUNT]-> (:Account {accountName: "hans-tablet"})
```

Kontakte und Termine gehören weiterhin zum `accountName`. Restore liefert dann alle Nodes aller verlinkten Accounts der Person. Account-Linking wird einmalig per API initiiert (z.B. `POST /person/link-account`). Ermöglicht auch Familien-Support (mehrere Personen, jede mit eigenen Accounts).

---

## 2. Was sich in den Entities ändert

### ContactEntity (graph/ContactNode.kt)

```kotlin
// VORHER (JPA)
@Entity @Table(name = "contacts")
data class ContactEntity(@Id @GeneratedValue val id: Long = 0, ...)

// NACHHER (Neo4j)
@Node("Contact")
class ContactNode(
    @Id @GeneratedValue val id: Long? = null,
    val lookupKey: String,
    val accountName: String,
    // ... alle Properties direkt (kein @Column nötig)

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
    override fun equals(other: Any?) = other is ContactNode && lookupKey == other.lookupKey
    override fun hashCode() = lookupKey.hashCode()
}

// Child-Nodes — kein @Table, kein contactId-FK mehr nötig
@Node("PhoneNumber")
data class PhoneNumberNode(@Id @GeneratedValue val id: Long? = null,
                           val number: String, val type: Int, val label: String? = null)
// ... analog für Email, PostalAddress, Organization, InstantMessenger
```

### AppointmentNode (graph/AppointmentNode.kt)

**Vereinheitlichtes Modell** — phone-Termine und Bookings sind dieselbe Klasse.
Kein `deviceId`, kein `source`. Stabile ID ist ein server-seitiger UUID.

```kotlin
@Node("Appointment")
class AppointmentNode(
    @Id @GeneratedValue val id: Long? = null,
    val appointmentId: String,   // UUID, stabiler Geschäfts-PK (server-seitig generiert)
    val accountName: String,
    val title: String,
    val description: String? = null,
    val dtStart: Long,
    val dtEnd: Long? = null,
    val duration: String? = null,
    val allDay: Boolean = false,
    val timezone: String,
    val rrule: String? = null,
    val location: String? = null,
    val organizer: String? = null,
    val calendarName: String? = null,
    val calendarAccountType: String? = null,
    val calendarAccountName: String? = null,
    val calendarColor: Int? = null,           // NEU: Kalenderfarbe für Restore auf neuem Gerät
    val status: String? = null,   // z.B. "confirmed", "pending" — nur bei Bookings gesetzt
    val lastUpdatedAt: Long,
    val createdAt: Long,

    @Relationship(type = "HAS_ATTENDEE", direction = OUTGOING)
    val attendees: MutableList<AttendeeNode> = mutableListOf(),

    // Nur bei Bookings befüllt — phone-importierte Termine haben keine Messages
    @Relationship(type = "HAS_MESSAGE", direction = OUTGOING)
    val messages: MutableList<MessageNode> = mutableListOf(),
) {
    override fun equals(other: Any?) = other is AppointmentNode && appointmentId == other.appointmentId
    override fun hashCode() = appointmentId.hashCode()
}

@Node("Attendee")
class AttendeeNode(
    @Id @GeneratedValue val id: Long? = null,
    val name: String?,
    val email: String?,
    val status: Int,

    // Optional — wird gesetzt wenn Attendee einem bekannten Kontakt entspricht.
    // Matching per E-Mail beim Speichern: contactRepository.findByAccountNameAndEmail(...)
    @Relationship(type = "IS_CONTACT", direction = OUTGOING)
    val contact: ContactNode? = null,
)

@Node("Message")
data class MessageNode(
    @Id @GeneratedValue val id: Long? = null,
    val senderName: String,
    val text: String,
    val createdAt: Long,
)
```

**Cypher-Beispiel** — Termine mit bekannten Kontakten als Attendees:
```cypher
MATCH (a:Appointment)-[:HAS_ATTENDEE]->(att:Attendee)-[:IS_CONTACT]->(c:Contact)
WHERE a.accountName = $accountName
RETURN a, att, c
```

---

## 3. Was sich in den Repositories ändert

```kotlin
// VORHER (JPA)
interface ContactRepository : JpaRepository<ContactEntity, Long> {
    @EntityGraph(attributePaths = [...])
    fun findAllByAccountName(accountName: String): List<ContactEntity>
    fun findByLookupKey(lookupKey: String): ContactEntity?
}

// NACHHER (Neo4j) — kein @EntityGraph nötig, Neo4j traversiert Beziehungen nativ
interface ContactRepository : Neo4jRepository<ContactNode, Long> {
    fun findAllByAccountName(accountName: String): List<ContactNode>
    fun findByLookupKey(lookupKey: String): ContactNode?
    fun findByAccountNameAndLookupKey(accountName: String, lookupKey: String): ContactNode?
}
```

- **`@EntityGraph` entfällt vollständig** — Spring Data Neo4j lädt verknüpfte Nodes automatisch
- **`FetchType.LAZY/EAGER` existiert in Neo4j nicht** — Tiefe wird über `depth`-Parameter gesteuert
- **Kein `MultipleBagFetchException`** — Neo4j-Problem nicht vorhanden

---

## 4. Was sich in den Controllern ändert

### ContactsController

| Vorher | Nachher |
|--------|---------|
| `contactRepository.findByLookupKey(dto.lookupKey)` | gleich (Methode bleibt) |
| `contactRepository.deleteById(existing.id)` | gleich |
| `@Transactional` auf GET-Methoden | bleibt (Neo4j hat eigene TX-Verwaltung) |
| Child-Entities mit `contactId: Long` | Child-Nodes ohne FK-Feld |

### Invitee-Filterlogik (BookingController) → entfällt

`invitees` gibt es nicht mehr — nur noch `Attendees`. Die 200/207-Logik entfällt damit ebenfalls.
Attendees werden direkt beim Anlegen eines Bookings mitgegeben und nicht separat validiert.

---

## 5. Offene Punkte — gelöst

### 5.1 AuthController — `deleteExpired()` entfernen

```kotlin
// VORHER — manueller Cleanup beim Login
sessionRepository.deleteExpired(System.currentTimeMillis())

// NACHHER — entfällt, Redis TTL löscht abgelaufene Sessions automatisch
```

### 5.2 IS_CONTACT-Matching — Cypher `@Query` im Repository

`ContactNode` hat keine `email`-Property direkt — Emails sind Child-Nodes. Matching braucht:

```kotlin
// graph/ContactRepository.kt
@Query("MATCH (c:Contact {accountName: \$accountName})-[:HAS_EMAIL]->(e:Email {address: \$email}) RETURN c")
fun findByAccountNameAndEmail(accountName: String, email: String): ContactNode?
```

Wird beim Speichern von Attendees aufgerufen — wenn Match gefunden, wird `[:IS_CONTACT]`-Kante gesetzt.

### 5.4 Account-Registrierung vs. Login — Checkbox in der App

**Problem:** Der aktuelle Server führt keinen Passwort-Check durch. Für Phase 2 (SharedCalendar) ist
ein echter Account mit Passwort nötig — andernfalls kann jeder beliebige `accountName` behaupten,
er gehöre ihm.

**Lösung:** Die App unterscheidet beim Konto-Setup zwischen Registrierung und Login per Checkbox.

#### App — `AddAccountActivity`

```
☑ Neues Konto anlegen      → POST /auth/register
☐ (nicht aktiviert)        → POST /auth/login  (Credentials werden geprüft)
```

| Checkbox | Endpunkt | Verhalten |
|----------|----------|-----------|
| ☑ aktiv | `POST /auth/register { username, password }` | Account anlegen, Token zurückgeben — `409 Conflict` wenn Username bereits vergeben |
| ☐ inaktiv | `POST /auth/login { username, password }` | Credentials prüfen — `401 Unauthorized` wenn falsch oder Account nicht vorhanden |

- Verhindert versehentliche Duplikate durch Tippfehler im Passwort
- `401`-Response beim Login enthält klare Fehlermeldung → App kann anbieten: "Konto nicht gefunden — neu anlegen?"

#### Server — `AuthController`

```kotlin
// Neuer Endpunkt
@PostMapping("/auth/register")
fun register(@RequestBody body: LoginRequest): ResponseEntity<LoginResponse> {
    if (accountRepository.existsByUsername(body.username))
        return ResponseEntity.status(409).build()
    val hash = BCrypt.hashpw(body.password, BCrypt.gensalt())
    accountRepository.save(AccountNode(username = body.username, passwordHash = hash))
    // Session anlegen + Token zurückgeben (wie /auth/login heute)
    ...
}

// Geänderter Login — prüft jetzt Passwort
@PostMapping("/auth/login")
fun login(@RequestBody body: LoginRequest): ResponseEntity<LoginResponse> {
    val account = accountRepository.findByUsername(body.username)
        ?: return ResponseEntity.status(401).build()
    if (!BCrypt.checkpw(body.password, account.passwordHash))
        return ResponseEntity.status(401).build()
    // Session anlegen + Token zurückgeben
    ...
}
```

#### Neo4j — `AccountNode` (graph/AccountNode.kt)

```kotlin
@Node("Account")
data class AccountNode(
    @Id @GeneratedValue val id: Long? = null,
    val username: String,
    val passwordHash: String,
    val createdAt: Long = System.currentTimeMillis(),
)
```

- `username` entspricht `accountName` — gleicher Wert, der in allen anderen Nodes als Property gespeichert ist
- `passwordHash` mit BCrypt — `spring-security-crypto` als einzige Security-Dependency nötig (kein Spring Security Web)
- `AccountNode` wird auch für `[:HAS_MEMBER]`-Kanten in SharedCalendars referenziert (Phase 2)

#### build.gradle.kts — Dependency

```kotlin
implementation("org.springframework.security:spring-security-crypto")
// kein spring-boot-starter-security nötig — nur Hashing, keine Filter-Chain
```

**Problem:** Ohne `deviceId` braucht der Server einen stabilen Identifier pro Kontakt/Termin,
der über Gerätewechsel und Restores hinweg erhalten bleibt.

**Lösung:** Die Android-App generiert beim ersten Sync pro Eintrag eine UUID und speichert
sie als Custom-Feld direkt im Android-Datensatz:

| Typ | Android-Speicherort | Custom Field |
|-----|---------------------|-------------|
| Kontakt | `ContactsContract.Data` | eigener MIME-Type `vnd.de.sync.contacts/uuid` |
| Termin | `CalendarContract.ExtendedProperties` | Key: `de.sync.contacts.uuid` |

**Ablauf:**
1. App prüft beim Sync pro Eintrag: Custom-UUID-Feld vorhanden?
2. Wenn nein → UUID generieren + ins Custom-Feld schreiben
3. UUID wird mit jedem Sync an den Server übertragen (`syncId`-Feld im DTO)
4. Server prüft: `findBySyncId(uuid)` → vorhanden → delete + save, nicht vorhanden → neu anlegen

**Vorteile:**
- UUID überlebt Gerätewechsel (steckt im Kontakt/Termin selbst, wird bei Backup mitgesichert)
- Multi-Device: zwei Geräte mit demselben Account sehen dieselbe UUID → kein Duplikat
- Bookings: Server generiert UUID beim `POST /booking` → Phone bekommt sie beim Restore

**Änderungen in den Nodes:**
```kotlin
@Node("Appointment")
class AppointmentNode(
    val syncId: String,      // UUID — von Phone generiert oder Server (bei Bookings)
    val accountName: String,
    // ...
) {
    override fun equals(other: Any?) = other is AppointmentNode && syncId == other.syncId
    override fun hashCode() = syncId.hashCode()
}
```

Analog für `ContactNode`: `syncId` ersetzt `lookupKey` als Upsert-Schlüssel.
`lookupKey` bleibt als zusätzliche Property erhalten (für Android-interne Referenzierung).

---

## 7. Was sich in der Konfiguration ändert

### DataConfig.kt

```kotlin
// VORHER
@EnableJpaRepositories(basePackages = ["de.sync.app.server.data"])
@EnableNeo4jRepositories(basePackages = ["de.sync.app.server.graph"])
@EnableRedisRepositories(basePackages = ["de.sync.app.server.cache"])

// NACHHER — JPA entfällt vollständig
@EnableNeo4jRepositories(basePackages = ["de.sync.app.server.graph"])   // Contact, Appointment
@EnableRedisRepositories(basePackages = ["de.sync.app.server.cache"])   // Session + SlotService-Cache
```

### application.properties

```properties
# MySQL entfällt vollständig — spring.datasource.* und spring.jpa.* werden entfernt

# Neo4j (bereits vorhanden)
spring.neo4j.uri=${NEO4J_URI:bolt://localhost:7687}
spring.neo4j.authentication.username=${NEO4J_USER:neo4j}
spring.neo4j.authentication.password=${NEO4J_PASSWORD:neo4jsecret}

# Redis (bereits vorhanden) — für Sessions + SlotService-Cache
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
```

### build.gradle.kts

`spring-boot-starter-data-neo4j` ist bereits eingebunden. Entfernen:
- `implementation("org.springframework.boot:spring-boot-starter-data-jpa")`
- `runtimeOnly("com.mysql:mysql-connector-j")`

---

## 8. Was wegfällt

| Was | Warum |
|-----|-------|
| `@EntityGraph` in Repositories | Neo4j traversiert Beziehungen nativ — kein N+1-Problem |
| `FetchType.LAZY/EAGER` | Konzept existiert in Neo4j nicht |
| `@JoinColumn`, `@OneToMany`, `@ManyToMany` | ersetzt durch `@Relationship` |
| `contactId: Long` in Child-Entities | kein FK-Feld nötig — Kante ersetzt FK |
| `invitees` / `booking_invitees` | entfällt — nur noch `Attendees` für alles |
| `deviceId` in AppointmentEntity | entfällt — ersetzt durch `syncId` (UUID) |
| `BookingEntity` als separate Klasse | entfällt — Bookings sind `AppointmentNode` |
| `migrate_contacts_pk.sql` | irrelevant — Neo4j hat keine FK-Constraints |
| MySQL-Container (sync-mysql) | **entfällt vollständig** aus `cluster-nas.bat` |
| `spring.datasource.*` + `spring.jpa.*` | entfallen aus `application.properties` |
| `spring-boot-starter-data-jpa` + MySQL-Driver | entfallen aus `build.gradle.kts` |
| `data/`-Package (JPA-Entities) | wird zu `graph/` (Neo4j) + `cache/` (Redis) |
| `SessionRepository.deleteExpired()` | entfällt — Redis TTL übernimmt das |
| `@Table`, `@Column`, `@GeneratedValue(IDENTITY)` | ersetzt durch Neo4j-Annotationen |

---

## 9. Datenmigration (einmalig)

Die bestehenden Kontakte und Termine in MySQL müssen einmalig nach Neo4j migriert werden.
Das Migrationsskript wird analog zu `migrate_contacts_pk.sql` als ausführbare Datei angelegt.

### Format

Python-Skript `migrate_to_neo4j.py` — liest aus MySQL, schreibt nach Neo4j:
- Abhängigkeiten: `mysql-connector-python`, `neo4j` (Python-Driver)
- Ausführbar direkt vom Terminal ohne Build-Schritt

### Ablauf

```
1. MySQL-Container läuft noch (sync-mysql)
2. Neo4j-Container läuft (sync-neo4j)
3. Skript ausführen — migriert Kontakte + Termine
4. Skript gibt Anzahl migrierter Nodes aus zur Verifikation
5. Server neu deployen (ohne JPA/MySQL-Dependencies)
6. MySQL-Container aus cluster-nas.bat entfernen + stoppen
```

### Terminalbefehl (von sync-app/ aus)

```bash
# Verbindung zum NAS, Python-Skript übertragen und ausführen:
ssh ruth@192.168.2.223 "python3 /tmp/migrate_to_neo4j.py \
  --mysql-host sync-mysql --mysql-user syncuser --mysql-pass <PASS> --mysql-db syncdb \
  --neo4j-uri bolt://sync-neo4j:7687 --neo4j-user neo4j --neo4j-pass neo4jsecret"
```

### Was das Skript anlegt

| MySQL-Tabelle | → Neo4j |
|---------------|---------|
| `contacts` | `(:Contact)` Node mit `syncId` (neu generiert) |
| `contact_phones` | `[:HAS_PHONE]->(:PhoneNumber)` |
| `contact_emails` | `[:HAS_EMAIL]->(:Email)` |
| `contact_addresses` | `[:HAS_ADDRESS]->(:PostalAddress)` |
| `contact_organizations` | `[:HAS_ORG]->(:Organization)` |
| `contact_instant_messengers` | `[:HAS_IM]->(:InstantMessenger)` |
| `appointments` | `(:Appointment)` Node mit `syncId` (neu generiert) |
| `appointment_attendees` | `[:HAS_ATTENDEE]->(:Attendee)` |
| `sessions` | wird **nicht** migriert — Redis-Sessions starten leer (alle Nutzer loggen sich neu ein) |

### Hinweis nach der Migration

Nach dem ersten Sync vom Phone werden die UUIDs (`syncId`) in den Android-Datensätzen gespeichert.
Bis dahin haben die migrierten Neo4j-Nodes server-seitig generierte UUIDs — beim nächsten
Phone-Sync werden sie per `lookupKey` (Kontakte) bzw. `dtStart+title` (Termine, Fallback) abgeglichen
und die Phone-UUID dann fest verknüpft.

---

## 11. Bidirektionaler Sync

### Ziel

Der aktuelle Sync ist **unidirektional**: Phone → Server. Ziel ist ein echter Zwei-Wege-Abgleich:

| Szenario | Beschreibung |
|----------|-------------|
| **Single Device** | Wie bisher — keine sichtbare Änderung |
| **Multi-Device (gleicher Account)** | Gerät A lädt hoch → Gerät B bekommt fehlende Einträge automatisch beim nächsten Sync |
| **Familienkalender** | Account A erstellt Termin in geteiltem Kalender → Gerät von Account B synchronisiert ihn automatisch |

---

### Phase 1 — Multi-Device (gleicher Account)

**Neuer Endpunkt: `POST /sync/manifest`**

Die App schickt eine Übersicht aller lokalen Einträge mit ihren Timestamps, bevor sie Daten hochlädt:

```json
{
  "accountName": "hans",
  "contacts": [
    { "syncId": "uuid-1", "lastUpdatedAt": 1712345678000 },
    { "syncId": "uuid-2", "lastUpdatedAt": 1712345678000 }
  ],
  "appointments": [
    { "syncId": "uuid-3", "lastUpdatedAt": 1712345678000 }
  ]
}
```

Der Server antwortet mit drei Kategorien pro Typ:

```json
{
  "contacts": {
    "toUpload":   ["uuid-x", "uuid-y"],
    "toDownload": [{ ...ContactDto... }],
    "toUpdate":   [{ ...ContactDto... }]
  },
  "appointments": {
    "toUpload":   ["uuid-z"],
    "toDownload": [{ ...AppointmentDto... }],
    "toUpdate":   [{ ...AppointmentDto... }]
  }
}
```

> `toDownload` enthält **nur LOCAL-Kalender-Termine** — Cloud-Kalender werden nicht auf das Gerät zurückgespielt (Google/Exchange synct sie selbst).

**Sync-Ablauf in der App (nach Manifest-Response):**

```
1. POST /sync/manifest → ManifestResponse
2. POST /contacts      → toUpload-Kontakte hochladen  (bestehender Endpunkt)
3. POST /appointments  → toUpload-Termine hochladen   (bestehender Endpunkt)
4. toDownload-Kontakte → ContactsContract.insert()    (ContactWriter)
5. toDownload-Termine  → CalendarContract.insert()    (CalendarWriter, nur LOCAL)
   → Kalender per calendarName suchen oder anlegen (mit calendarColor)
6. toUpdate-Kontakte   → ContactsContract.update() per lookupKey
7. toUpdate-Termine    → CalendarContract.update() per syncId (ExtendedProperties, nur LOCAL)
```

**Serverlogik `POST /sync/manifest` — Appointments:**

```kotlin
// Pseudocode
val localSyncIds  = request.appointments.map { it.syncId }.toSet()
val serverNodes   = appointmentRepository.findAllByAccountName(accountName)
val serverSyncIds = serverNodes.map { it.syncId }.toSet()

val toUpload = localSyncIds - serverSyncIds   // fehlt auf Server → hochladen

// Deletion-Tracking: differenziert nach Kalendertyp
val missingOnPhone = serverNodes.filter { it.syncId !in localSyncIds }
missingOnPhone.forEach { node ->
    if (node.calendarAccountType != "LOCAL") {
        // Cloud-Kalender: Phone ist Source of Truth → serverseitig löschen
        appointmentRepository.deleteById(node.id!!)
    }
    // LOCAL-Kalender: Server ist Source of Truth → in toDownload schicken (s.u.)
}

val toDownload = serverNodes.filter {
    it.syncId !in localSyncIds && it.calendarAccountType == "LOCAL"
}
val toUpdate = serverNodes.filter { node ->
    val local = request.appointments.find { it.syncId == node.syncId }
    local != null && node.lastUpdatedAt > local.lastUpdatedAt
}
```

> **Slot-Checker:** `GET /booking/slots/available` prüft **alle** `(:Appointment)`-Nodes des Accounts
> (local + cloud) → verhindert Doppelbuchungen über alle Kalender hinweg.

---

### Phase 2 — Familienkalender (SharedCalendar)

**Graphmodell-Erweiterung:**

```
(:SharedCalendar {id: UUID, name: "Familienkalender", ownerAccount: "hans"})
  -[:HAS_MEMBER]-> (:Account {name: "hans"})
  -[:HAS_MEMBER]-> (:Account {name: "anna"})

(:Appointment {syncId: ..., accountName: "hans"})
  -[:BELONGS_TO]-> (:SharedCalendar)
```

- `(:Account)` ist der `AccountNode` aus Abschnitt 5.4 (mit Passwort-Hash) — kein separates Auth-Objekt nötig
- Beim Anlegen eines SharedCalendars: `POST /calendar` → erzeugt `(:SharedCalendar)` + `[:HAS_MEMBER]` für Owner
- Einladen per **Invite-Code**: `POST /calendar/:id/invite` → erzeugt 6-stelligen Code (TTL 10 Min, in Redis) → B gibt Code in App ein → `POST /calendar/:id/join { inviteCode }` → Server prüft Code, fügt `[:HAS_MEMBER]->(:Account)` hinzu
- Termin wird dem SharedCalendar zugeordnet: `POST /appointment` mit `sharedCalendarId` → `[:BELONGS_TO]` Kante

**Invite-Code-Flow:**

```
A: POST /calendar/:id/invite            → { inviteCode: "X7K2PQ" }  (Redis, TTL 10 Min)
   → Code out-of-band an B weitergeben (zeigen, tippen, QR-Code)

B: POST /calendar/:id/join { inviteCode: "X7K2PQ" }
   → Server: Code in Redis vorhanden + gültig?
   → Ja: [:HAS_MEMBER]->(:Account {name: B's accountName aus Token}) anlegen
   → Code aus Redis löschen (einmalig nutzbar)
```

- Kein Passworttausch zwischen Geräten
- Server zieht `accountName` aus dem `X-Sync-Token` → kein Spoofing möglich
- Invite-Code in Redis gespeichert (passt perfekt zum bestehenden Redis-Setup)

**Manifest-Response-Erweiterung:**

Beim `POST /sync/manifest` prüft der Server zusätzlich, ob der Account Mitglied in SharedCalendars ist:

```kotlin
// Termine aus eigenen SharedCalendars abrufen
val sharedCalendars = sharedCalendarRepository.findAllByMemberAccount(accountName)
val sharedAppointments = sharedCalendars
    .flatMap { appointmentRepository.findAllBySharedCalendar(it) }
    .filter { it.syncId !in localSyncIds }   // nur fehlende
```

Diese werden in `toDownload` der appointments-Response ergänzt.

**Neue Endpunkte (Phase 2):**

| Method | Endpunkt | Beschreibung |
|--------|----------|-------------|
| `POST /auth/register` | — | Neuen Account registrieren (BCrypt) — `409` wenn Username vergeben |
| `POST` | `/calendar` | SharedCalendar anlegen (Owner = Token-Account) |
| `GET`  | `/calendar` | Alle SharedCalendars des Accounts |
| `POST` | `/calendar/:id/invite` | Invite-Code generieren (Redis, TTL 10 Min) |
| `POST` | `/calendar/:id/join` | Invite-Code einlösen → Mitglied werden |
| `DELETE` | `/calendar/:id/member/:accountName` | Mitglied entfernen (nur Owner) |
| `DELETE` | `/calendar/:id` | SharedCalendar löschen (nur Owner) |

---

### Priorisierung

**Jetzt umbauen (Phase 1 — beim Neo4j-Umbau berücksichtigen):**
- `AccountNode` + `POST /auth/register` + Passwort-Check in `POST /auth/login` (Abschnitt 5.4)
- App: Checkbox "Neues Konto anlegen" in `AddAccountActivity`
- `POST /sync/manifest` implementieren (Server + App)
- App: `toDownload`/`toUpdate` in `ContactsContract` + `CalendarContract` schreiben
- App: `ContactWriter` + `CalendarWriter` anlegen (analog zu den bestehenden `Reader`-Klassen)

**Später (Phase 2 — nach stabilem Sync):**
- `SharedCalendarNode` + `AccountNode` + `HAS_MEMBER`-Kante
- Neue `/calendar`-Endpunkte
- Manifest-Response um geteilte Termine erweitern

---

## 10. Aufwandsübersicht

| Aufgabe | Dateien | Aufwand |
|---------|---------|---------|
| `ContactNode` + 5 Child-Nodes + `syncId` | `graph/ContactNode.kt` | mittel |
| `AppointmentNode` + `AttendeeNode` + `MessageNode` (kein `deviceId`, mit `syncId`) | `graph/AppointmentNode.kt` | mittel |
| `SessionEntity` → Redis (`@RedisHash`) | `cache/SessionEntity.kt` | trivial |
| `SessionRepository` → `CrudRepository` | `cache/SessionRepository.kt` | trivial |
| `ContactRepository` — `findByAccountNameAndEmail` (`@Query`) | `graph/ContactRepository.kt` | klein |
| `AppointmentRepository` — `findBySyncId` | `graph/AppointmentRepository.kt` | trivial |
| `ContactsController` anpassen | `ContactsController.kt` | klein |
| `AppointmentsController` anpassen | `AppointmentsController.kt` | klein |
| `AuthController` — `deleteExpired()` entfernen | `AuthController.kt` | trivial |
| `DataConfig.kt` — JPA entfernen | `DataConfig.kt` | trivial |
| `build.gradle.kts` — JPA/MySQL entfernen | `build.gradle.kts` | trivial |
| `application.properties` — MySQL entfernen | `application.properties` | trivial |
| `cluster-nas.bat` — MySQL-Container entfernen | `cluster-nas.bat` | klein |
| Android-App — `syncId` als Custom-Feld (Kontakte + Termine) | `ContactReader.kt`, `CalendarReader.kt` | mittel |
| Datenmigration MySQL → Neo4j (`migrate_to_neo4j.py`) | neues Skript | mittel |
| **Bidirektionaler Sync — Server** | | |
| `POST /sync/manifest` Endpunkt (Manifest-Vergleich, Response-Typen) | `SyncController.kt` | mittel |
| **Bidirektionaler Sync — App** | | |
| `ContactWriter` — schreibt `toDownload`/`toUpdate` in `ContactsContract` | `sync/ContactWriter.kt` | mittel |
| `CalendarWriter` — schreibt `toDownload`/`toUpdate` in `CalendarContract` | `sync/CalendarWriter.kt` | mittel |
| `ContactSyncWorker` + `CalendarSyncWorker` — Manifest-Flow integrieren | `sync/ContactSyncWorker.kt`, `sync/CalendarSyncWorker.kt` | klein |
| **Familienkalender (Phase 2 — nach stabilem Sync)** | | |
| `SharedCalendarNode` + `AccountNode` + `HAS_MEMBER`-Kante | `graph/SharedCalendarNode.kt` | klein |
| `/calendar`-Endpunkte (CRUD + Member-Management) | `SharedCalendarController.kt` | mittel |
| Manifest-Response um SharedCalendar-Termine erweitern | `SyncController.kt` | klein |
