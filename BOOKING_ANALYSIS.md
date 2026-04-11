# Booking-Modul: Analyse & Implementierungsplan

Dieses Dokument beschreibt den Port des Booking-Systems aus `homeservice` (NestJS/Neo4j/Redis)
in den `sync-app-server` (Spring Boot / Kotlin / MySQL + Neo4j).

---

## Status

| Schritt | Status |
|---|---|
| Neo4j-Integration (Spring Data Neo4j) | ✅ erledigt |
| `DataConfig.kt` — JPA + Neo4j + Redis Package-Trennung | ✅ erledigt |
| `RedisConfig.kt` — StringRedisTemplate + jsonRedisTemplate | ✅ erledigt |
| `cluster-nas.bat` — Neo4j + Redis Container + Volumes | ✅ erledigt |
| `application.properties` — Neo4j + Redis Properties | ✅ erledigt |
| `graph/`-Package als Platzhalter für Neo4j-Entities | ✅ erledigt |
| `cache/`-Package als Platzhalter für Redis-Entities | ✅ erledigt |
| `BookingEntity.kt` + `BookingMessageEntity.kt` | ⬜ ausstehend |
| `BookingRepository.kt` | ⬜ ausstehend |
| `BookingController.kt` | ⬜ ausstehend |
| `SlotService.kt` | ⬜ ausstehend |
| `AppointmentRepository` — `dtStartBetween`-Methode | ⬜ ausstehend |

---

## 1. Ausgangslage

### homeservice (Quelle)
- **Framework:** NestJS (TypeScript)
- **Datenbank:** Neo4j (Graphdatenbank) — Bookings sind Nodes, keine echten Relationen
- **Cache:** Redis — tagesweise Buchungsslots für schnelle Freizeitsuche
- **Auth:** JWT mit User-Entität (`req.user.id`)
- **Invitees:** Array von User-IDs, als Property auf dem Booking-Node gespeichert
- **Messages:** Separate Neo4j-Nodes mit `bookingId`-Property (kein FK, kein Join)

### sync-app-server (Ziel) — aktueller Stand
- **Framework:** Spring Boot 3.5 (Kotlin)
- **Datenbank MySQL:** JPA/Hibernate — `contacts`, `appointments`, `sessions`, ...
- **Datenbank Neo4j:** ✅ Spring Data Neo4j integriert (Dependency + Config + Container)
- **Cache Redis:** ✅ Spring Data Redis integriert (Dependency + Config + Container)
- **Cache:** Redis verfügbar — SlotService kann tagesweise Buckets wie in homeservice cachen
- **Auth:** Token-basiert über `sessions`-Tabelle, kein User-Entity — nur `accountName` (String)
- **Packages:**
  - `data/` → JPA-Repositories (MySQL)
  - `graph/` → Neo4j-Repositories (Neo4j) — aktuell leer, bereit für Booking-Nodes
  - `cache/` → Redis-Repositories (Redis) — aktuell leer, bereit für @RedisHash-Entities

---

## 2. Was wurde bereits implementiert

### ✅ Neo4j-Integration (09.04.2026)

**`build.gradle.kts`**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
```

**`application.properties`**
```properties
spring.neo4j.uri=${NEO4J_URI:bolt://localhost:7687}
spring.neo4j.authentication.username=${NEO4J_USER:neo4j}
spring.neo4j.authentication.password=${NEO4J_PASSWORD:neo4jsecret}
```

**`DataConfig.kt`** — verhindert Konflikte zwischen JPA und Neo4j:
```kotlin
@Configuration
@EnableJpaRepositories(basePackages = ["de.sync.app.server.data"])
@EnableNeo4jRepositories(basePackages = ["de.sync.app.server.graph"])
class DataConfig
```

**`cluster-nas.bat`** — Neo4j-Container:
- Container: `sync-neo4j` (Image: `neo4j:5`)
- Ports: 7474 (Browser UI), 7687 (Bolt)
- Volume: `sync-neo4j-data`
- Health-Check via HTTP auf Port 7474
- App-Container bekommt `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD` als Env-Vars

---

### ✅ Redis-Integration (09.04.2026)

**`build.gradle.kts`**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

**`application.properties`**
```properties
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.timeout=2000ms
```

**`RedisConfig.kt`** — zwei Templates für unterschiedliche Nutzung:
```kotlin
// 1. StringRedisTemplate — auto-configured, für einfache String-Operationen
redis.opsForValue().set("key", "value")

// 2. jsonRedisTemplate — Jackson-JSON-Serialisierung, für Objekte/Listen
// @Qualifier("jsonRedisTemplate")
jsonRedis.opsForValue().set("slots:2024-03-15", listOf(...))
```

**`DataConfig.kt`** — erweitert um Redis:
```kotlin
@EnableRedisRepositories(basePackages = ["de.sync.app.server.cache"])
```

**`cluster-nas.bat`** — Redis-Container:
- Container: `sync-redis` (Image: `redis:7`)
- Port: 6379
- Volume: `sync-redis-data` (Persistenz via `--save 60 1`)
- Health-Check via `redis-cli ping`
- App-Container bekommt `REDIS_HOST`, `REDIS_PORT` als Env-Vars

---

## 3. Endpunkte: Vergleich und Übernahme

### Bereits vorhanden (keine Überlappung — andere Aufgabe)

| Endpunkt | Beschreibung |
|---|---|
| `POST /appointments` | Batch-Upsert aus Android-Kalender-Sync — kein manuelles Anlegen |
| `GET /appointments?accountName=` | Alle Appointments eines Accounts für Restore |
| `GET /appointments/count?accountName=` | Anzahl gespeicherter Appointments |

Diese Endpunkte erledigen **nicht** dieselbe Aufgabe wie Bookings. Appointments sind
automatisch synchronisierte Android-Kalendereinträge. Bookings sind manuell angelegte
Termine mit Eingeladenen und Chat.

### Neu zu implementieren

| Methode | Route | Entsprechung homeservice |
|---|---|---|
| `GET` | `/booking/slots/available?from=&to=&duration=` | SlotService.findFreeSlotsForRange |
| `GET` | `/booking?accountName=` | BookingsService.findAll (gefiltert nach accountName) |
| `GET` | `/booking/:id` | BookingsService.findById |
| `POST` | `/booking` | BookingsService.create |
| `PUT` | `/booking/:id` | BookingsService.update |
| `DELETE` | `/booking/:id` | BookingsService.delete |
| `POST` | `/booking/:id/message` | MessagesService.create |
| `GET` | `/booking/:id/chat` | MessagesService.findByBookingId |
| `DELETE` | `/booking/:id/message/:messageId` | MessagesService.delete |
| `POST` | `/booking/:id/invitees` | BookingsService.addInvitee |
| `DELETE` | `/booking/:id/invitees/:lookupKey` | BookingsService.removeInvitee |
| `GET` | `/booking/:id/invitees` | BookingsService.getInvitees |

**Hinweis zu `DELETE /booking/:id/invitees/:lookupKey`:** homeservice verwendet eine
numerische `attendeeId` (User-ID). In der Kotlin-Version ist der Identifier der `lookup_key`
(String) aus der `contacts`-Tabelle. Das weicht vom Original ab, ist aber unvermeidlich.

---

## 4. Datenmodell

### 4.1 Tabelle `bookings`

```sql
CREATE TABLE bookings (
    id           VARCHAR(36)  PRIMARY KEY,    -- UUID
    account_name VARCHAR(255) NOT NULL,        -- Ersteller (kein User-Entity, nur accountName)
    title        TEXT         NOT NULL,
    description  TEXT,
    start_time   BIGINT       NOT NULL,        -- Unix ms
    end_time     BIGINT       NOT NULL,        -- Unix ms
    location_name TEXT,
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);
```

### 4.2 Tabelle `booking_invitees` (echte Join-Tabelle / Relation)

```sql
CREATE TABLE booking_invitees (
    booking_id  VARCHAR(36) NOT NULL,
    contact_id  BIGINT      NOT NULL,
    PRIMARY KEY (booking_id, contact_id),
    CONSTRAINT fk_bi_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)   ON DELETE CASCADE,
    CONSTRAINT fk_bi_contact FOREIGN KEY (contact_id) REFERENCES contacts(id)   ON DELETE CASCADE
);
```

**Hinweis:** `ON DELETE CASCADE` auf contacts bedeutet: wird ein Kontakt gelöscht, wird
der Invitee-Eintrag automatisch entfernt. Das Booking selbst bleibt bestehen.

**Bedingung — nur eigene Kontakte:** Beim `POST /booking/:id/invitees` wird server-seitig
geprüft, ob der Kontakt (`contacts.id`) zum gleichen `accountName` gehört wie das Booking.
Fremde Kontakte werden mit `HTTP 403` abgelehnt. Dies wird **nicht** allein über FK-Constraints
abgesichert, sondern explizit im Controller validiert.

### 4.3 Tabelle `booking_messages`

```sql
CREATE TABLE booking_messages (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    booking_id  VARCHAR(36)  NOT NULL,
    sender_name TEXT         NOT NULL,    -- accountName o.ä., da kein User-Entity
    text        TEXT         NOT NULL,
    created_at  BIGINT       NOT NULL,
    CONSTRAINT fk_bm_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE
);
```

### 4.4 JPA-Entities (Kotlin)

```kotlin
// BookingEntity.kt — reguläre class (kein data class!) wegen Hibernate @ManyToMany
@Entity
@Table(name = "bookings")
class BookingEntity(
    @Id val id: String,
    @Column(name = "account_name", nullable = false) val accountName: String,
    @Column(columnDefinition = "TEXT", nullable = false) val title: String,
    @Column(columnDefinition = "TEXT") val description: String? = null,
    @Column(name = "start_time", nullable = false) val startTime: Long,
    @Column(name = "end_time", nullable = false) val endTime: Long,
    @Column(name = "location_name", columnDefinition = "TEXT") val locationName: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Long,
    @Column(name = "updated_at", nullable = false) var updatedAt: Long,

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "booking_invitees",
        joinColumns = [JoinColumn(name = "booking_id")],
        inverseJoinColumns = [JoinColumn(name = "contact_id")]
    )
    val invitees: MutableSet<ContactEntity> = mutableSetOf(),  // Set statt List → kein MultipleBagFetchException

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    val messages: MutableList<BookingMessageEntity> = mutableListOf(),
) {
    override fun equals(other: Any?) = other is BookingEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

// BookingMessageEntity.kt
@Entity
@Table(name = "booking_messages")
data class BookingMessageEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "booking_id", nullable = false) val bookingId: String,
    @Column(name = "sender_name", columnDefinition = "TEXT", nullable = false) val senderName: String,
    @Column(columnDefinition = "TEXT", nullable = false) val text: String,
    @Column(name = "created_at", nullable = false) val createdAt: Long,
)
```

---

## 5. SlotService (Port des TS-Algorithmus)

Der Algorithmus aus `slot.service.ts` wird 1:1 nach Kotlin portiert.
**Redis ist verfügbar** — der SlotService kann wie in homeservice tagesweise Buckets
in Redis cachen (`slots:YYYY-MM-DD` → JSON-Liste von Buchungen).

```
Eingabe: from (ISO), to (ISO), duration (Minuten), accountName

1. Für jeden Tag im Bereich [from, to]:
   a. Redis-Key prüfen: "slots:<accountName>:<YYYY-MM-DD>"
   b. Cache-Hit → direkt aus Redis lesen
   c. Cache-Miss → AppointmentRepository + BookingRepository abfragen,
      Ergebnis in Redis schreiben (TTL: 1 Tag)

2. Jeden Zeitraum um 5 Minuten puffern (vor + nach dem Termin)

3. Überlappende Zeiträume zusammenführen (merge-Algorithmus)

4. Lücken >= durationMinutes als freie Slots zurückgeben

5. Alle Grenzen auf 15-Minuten-Raster runden

Ausgabe: List<TimeSlot> { start: ISO-String, end: ISO-String }
```

**Cache-Invalidierung:** Bei `POST /booking` (create/update/delete) muss der
betroffene Redis-Key `slots:<accountName>:<YYYY-MM-DD>` gelöscht werden,
damit die nächste Slot-Anfrage aktuelle Daten sieht.

Redis-Template für Slot-Buckets: `jsonRedisTemplate` (`@Qualifier("jsonRedisTemplate")`)

---

## 6. Auth-Kontext: Wer ist der Ersteller?

homeservice liest `req.user.id` aus dem JWT → numerische User-ID.

Der sync-app-server hat **kein User-Entity**. Auth läuft über Token → `sessions.account_name`.

**Lösung:** Der Controller liest den Token aus dem Header `X-Sync-Token`, schlägt ihn in
`sessions` nach, und verwendet `accountName` als Ersteller-Identifikator.

```kotlin
// Im Controller:
@PostMapping
fun createBooking(
    @RequestHeader("X-Sync-Token") token: String,
    @RequestBody dto: CreateBookingRequest,
): ResponseEntity<BookingResponse> {
    val accountName = sessionRepository.findById(token)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED) }
        .accountName
    return ResponseEntity.ok(bookingService.create(accountName, dto))
}
```

---

## 7. Probleme und Schwierigkeiten

### ⚠️ Problem 1 — Kein User-System

homeservice ordnet Bookings und Messages einem **User** zu (`creatorId`, `userId`).
Der sync-app-server kennt nur `accountName` (String). Nachrichten können keinem
bestimmten Gegenüber zugeordnet werden — es gibt nur einen Account.

**Konsequenz:**
- `creatorId` → `accountName` (String)
- `userId` in Messages → `senderName` (String, frei wählbar, z.B. "Hans")
- Keine Validierung wer eine Message absetzt — jeder mit gültigem Token kann schreiben

**Empfehlung:** Akzeptieren. Der Server ist ein privates Single-Tenant-System.

---

### ✅ Problem 2 — Invitee-FK auf contacts.id (BIGINT) — gelöst

Invitees sind Kontakte aus der `contacts`-Tabelle. Diese sind **accountgebunden** —
ein Kontakt gehört immer zu einem bestimmten `accountName`.

**Lösung:** `booking_invitees` verwendet `contact_id BIGINT` als FK auf `contacts.id`
(numerischer PK nach Migration vom 09.04.2026).

**Validierung im Controller:** Beim `POST /booking/:id/invitees` werden eingehende `contactId`s
gegen `contacts.account_name == booking.account_name` geprüft. Kontakte anderer Accounts werden
**still herausgefiltert** — kein Fehler, nur die eigenen Kontakte aus der Liste werden gespeichert.
Der Response gibt zurück, wie viele Invitees tatsächlich gespeichert wurden.

---

### ⚠️ Problem 3 — `@ManyToMany` und Hibernate data class ✅ gelöst

Kotlin `data class` + Hibernate `@ManyToMany` ist problematisch: `data class`
generiert `equals()` und `hashCode()` über alle Felder — das bricht den Hibernate
Lazy-Loading-Proxy.

**Lösung (bereits im Datenmodell berücksichtigt):** `BookingEntity` als reguläre
`class` mit `equals`/`hashCode` nur auf `id`.

---

### ✅ Problem 4 — Hibernate ddl-auto=update und `@ManyToMany` — gelöst

Hibernate `ddl-auto=update` legt `bookings`, `booking_invitees` und `booking_messages`
beim **ersten Start automatisch an** — kein manuelles SQL-Skript nötig vor dem Deployment.

**Was Hibernate NICHT anlegt:** `ON DELETE CASCADE` auf der FK-Constraint von `booking_invitees`.

**Konsequenz:** Wird ein Kontakt gelöscht, bleibt der Eintrag in `booking_invitees` stehen
(verwaiste Zeile). Das Booking selbst ist nicht betroffen. Hibernate räumt beim nächsten Laden
des `BookingEntity` die fehlenden Kontakte aus der `invitees`-Liste heraus (da kein Join-Treffer).

**Optional — nach erstem Deployment manuell ausführen** (empfohlen, aber kein Blocker):
```sql
ALTER TABLE booking_invitees
  ADD CONSTRAINT fk_bi_contact
  FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE;
```

---

### ⚠️ Problem 5 — Slot-Suche über zwei Tabellen

Der Slot-Algorithmus muss **Appointments** (Android-Sync) und **Bookings** (manuell)
kombinieren. `AppointmentEntity` hat noch kein `dtStartBetween`-Query.

**Lösung:** Neue Repository-Methode hinzufügen:
```kotlin
fun findAllByAccountNameAndDtStartBetween(
    accountName: String, from: Long, to: Long
): List<AppointmentEntity>
```

---

### ⚠️ Problem 6 — `GET /booking/:id/invitees` gibt lookup_keys zurück

homeservice gibt ein Array von User-IDs zurück (`[2, 3]`).
In der Kotlin-Version sind Invitees Kontakte — sinnvoller wäre eine Liste von
`{ lookupKey, displayName }` statt rohen IDs.

**Empfehlung:** Rückgabe als Array von Kontakt-Objekten (oder zumindest `lookupKey` + `displayName`),
nicht nur reine IDs.

---

## 8. Neue Dateien (Übersicht)

```
sync-app-server/src/main/kotlin/de/sync/app/server/
├── DataConfig.kt                  ✅ JPA + Neo4j + Redis Package-Trennung
├── RedisConfig.kt                 ✅ StringRedisTemplate + jsonRedisTemplate
├── graph/                         ✅ Package für Neo4j @Node-Entities (aktuell leer)
├── cache/                         ✅ Package für Redis @RedisHash-Entities (aktuell leer)
├── data/
│   ├── BookingEntity.kt           ⬜ BookingEntity (class, nicht data class) + BookingMessageEntity
│   └── BookingRepository.kt       ⬜ JpaRepository<BookingEntity, String>
├── BookingController.kt           ⬜ alle /booking/... Endpunkte
└── SlotService.kt                 ⬜ Port des TS-Algorithmus (mit Redis-Cache)
```

Geändert:
```
build.gradle.kts                   ✅ spring-boot-starter-data-neo4j + spring-boot-starter-data-redis
application.properties             ✅ spring.neo4j.* + spring.data.redis.*
cluster-nas.bat                    ✅ sync-neo4j + sync-redis Container + NEO4J_*/REDIS_* Env-Vars
data/AppointmentRepository.kt      ⬜ neue Methode findAllByAccountNameAndDtStartBetween
```

---

## 9. Offene Fragen

1. **`GET /booking`** — gefiltert nach `accountName` aus Token, oder alle?
2. **`senderName` bei Messages** — frei wählbar im Request-Body, oder immer aus Token (`accountName`)?
3. **Invitees-Response** — nur `contact_id`-Array, oder vollständiges Kontaktobjekt (`contactId`, `lookupKey`, `displayName`)?
4. **Slot-Suche** — nur Appointments + Bookings, oder auch externe Termine berücksichtigen?

### ✅ Entschieden (11.04.2026)

- **Invitees — Filterung statt Fehler:** Fremde Kontakte werden still herausgefiltert. Nur Kontakte mit `account_name == booking.account_name` werden gespeichert. Response: `200 OK` wenn alle gespeichert, `207 Multi-Status` wenn mindestens ein Kontakt herausgefiltert wurde. Body immer `{ saved, filtered, invitees: [...] }`.
- **`booking_invitees.contact_id`:** BIGINT FK auf `contacts.id` (nicht mehr `lookup_key`).
- **Problem 4 — kein Pre-Deployment-Skript nötig:** Hibernate legt alle 3 Tabellen automatisch an. `ON DELETE CASCADE` ist optional und kann nachträglich manuell hinzugefügt werden.


Dieses Dokument beschreibt den Port des Booking-Systems aus `homeservice` (NestJS/Neo4j/Redis)
in den `sync-app-server` (Spring Boot / Kotlin / MySQL).

---

## 1. Ausgangslage

### homeservice (Quelle)
- **Framework:** NestJS (TypeScript)
- **Datenbank:** Neo4j (Graphdatenbank) — Bookings sind Nodes, keine echten Relationen
- **Cache:** Redis — tagesweise Buchungsslots für schnelle Freizeitsuche
- **Auth:** JWT mit User-Entität (`req.user.id`)
- **Invitees:** Array von User-IDs, als Property auf dem Booking-Node gespeichert
- **Messages:** Separate Neo4j-Nodes mit `bookingId`-Property (kein FK, kein Join)

### sync-app-server (Ziel)
- **Framework:** Spring Boot 3.5 (Kotlin)
- **Datenbank:** MySQL 8.4 mit JPA/Hibernate
- **Cache:** keiner (kein Redis)
- **Auth:** Token-basiert über `sessions`-Tabelle, kein User-Entity — nur `accountName` (String)
- **Bestehende Entities:** `contacts`, `contact_emails/phones/...`, `appointments`, `appointment_attendees`, `sessions`

---

## 2. Endpunkte: Vergleich und Übernahme

### Bereits vorhanden (keine Überlappung — andere Aufgabe)

| Endpunkt | Beschreibung |
|---|---|
| `POST /appointments` | Batch-Upsert aus Android-Kalender-Sync — kein manuelles Anlegen |
| `GET /appointments?accountName=` | Alle Appointments eines Accounts für Restore |
| `GET /appointments/count?accountName=` | Anzahl gespeicherter Appointments |

Diese Endpunkte erledigen **nicht** dieselbe Aufgabe wie Bookings. Appointments sind
automatisch synchronisierte Android-Kalendereinträge. Bookings sind manuell angelegte
Termine mit Eingeladenen und Chat.

### Neu zu implementieren

| Methode | Route | Entsprechung homeservice |
|---|---|---|
| `GET` | `/booking/slots/available?from=&to=&duration=` | SlotService.findFreeSlotsForRange |
| `GET` | `/booking?accountName=` | BookingsService.findAll (gefiltert nach accountName) |
| `GET` | `/booking/:id` | BookingsService.findById |
| `POST` | `/booking` | BookingsService.create |
| `PUT` | `/booking/:id` | BookingsService.update |
| `DELETE` | `/booking/:id` | BookingsService.delete |
| `POST` | `/booking/:id/message` | MessagesService.create |
| `GET` | `/booking/:id/chat` | MessagesService.findByBookingId |
| `DELETE` | `/booking/:id/message/:messageId` | MessagesService.delete |
| `POST` | `/booking/:id/invitees` | BookingsService.addInvitee |
| `DELETE` | `/booking/:id/invitees/:lookupKey` | BookingsService.removeInvitee |
| `GET` | `/booking/:id/invitees` | BookingsService.getInvitees |

**Hinweis zu `DELETE /booking/:id/invitees/:lookupKey`:** homeservice verwendet eine
numerische `attendeeId` (User-ID). In der Kotlin-Version ist der Identifier der `lookup_key`
(String) aus der `contacts`-Tabelle. Das weicht vom Original ab, ist aber unvermeidlich.

---

## 3. Datenmodell

### 3.1 Tabelle `bookings`

```sql
CREATE TABLE bookings (
    id           VARCHAR(36)  PRIMARY KEY,    -- UUID
    account_name VARCHAR(255) NOT NULL,        -- Ersteller (kein User-Entity, nur accountName)
    title        TEXT         NOT NULL,
    description  TEXT,
    start_time   BIGINT       NOT NULL,        -- Unix ms
    end_time     BIGINT       NOT NULL,        -- Unix ms
    location_name TEXT,
    created_at   BIGINT       NOT NULL,
    updated_at   BIGINT       NOT NULL
);
```

### 3.2 Tabelle `booking_invitees` (echte Join-Tabelle / Relation)

```sql
CREATE TABLE booking_invitees (
    booking_id  VARCHAR(36)  NOT NULL,
    lookup_key  VARCHAR(255) NOT NULL,
    PRIMARY KEY (booking_id, lookup_key),
    CONSTRAINT fk_bi_booking  FOREIGN KEY (booking_id) REFERENCES bookings(id)  ON DELETE CASCADE,
    CONSTRAINT fk_bi_contact  FOREIGN KEY (lookup_key) REFERENCES contacts(lookup_key) ON DELETE CASCADE
);
```

**Hinweis:** `ON DELETE CASCADE` auf contacts bedeutet: wird ein Kontakt gelöscht, wird
der Invitee-Eintrag automatisch entfernt. Das Booking selbst bleibt bestehen.

### 3.3 Tabelle `booking_messages`

```sql
CREATE TABLE booking_messages (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    booking_id  VARCHAR(36)  NOT NULL,
    sender_name TEXT         NOT NULL,    -- accountName o.ä., da kein User-Entity
    text        TEXT         NOT NULL,
    created_at  BIGINT       NOT NULL,
    CONSTRAINT fk_bm_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE
);
```

### 3.4 JPA-Entities (Kotlin)

```kotlin
// BookingEntity.kt
@Entity
@Table(name = "bookings")
data class BookingEntity(
    @Id val id: String,
    @Column(name = "account_name", nullable = false) val accountName: String,
    @Column(columnDefinition = "TEXT", nullable = false) val title: String,
    @Column(columnDefinition = "TEXT") val description: String? = null,
    @Column(name = "start_time", nullable = false) val startTime: Long,
    @Column(name = "end_time", nullable = false) val endTime: Long,
    @Column(name = "location_name", columnDefinition = "TEXT") val locationName: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Long,
    @Column(name = "updated_at", nullable = false) val updatedAt: Long,

    // Relation zu Kontakten über booking_invitees
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "booking_invitees",
        joinColumns = [JoinColumn(name = "booking_id")],
        inverseJoinColumns = [JoinColumn(name = "contact_id")]
    )
    val invitees: MutableSet<ContactEntity> = mutableSetOf(),  // Set statt List → kein MultipleBagFetchException

    // Nachrichten
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    val messages: MutableList<BookingMessageEntity> = mutableListOf(),
)

// BookingMessageEntity.kt
@Entity
@Table(name = "booking_messages")
data class BookingMessageEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "booking_id", nullable = false) val bookingId: String,
    @Column(name = "sender_name", columnDefinition = "TEXT", nullable = false) val senderName: String,
    @Column(columnDefinition = "TEXT", nullable = false) val text: String,
    @Column(name = "created_at", nullable = false) val createdAt: Long,
)
```

---

## 4. SlotService (Port des TS-Algorithmus)

Der Algorithmus aus `slot.service.ts` wird 1:1 nach Kotlin portiert.
**Kein Redis** — stattdessen direkter MySQL-Query.

```
Eingabe: from (ISO), to (ISO), duration (Minuten)

1. AppointmentRepository.findAllByAccountNameAndStartTimeBetween(accountName, fromMs, toMs)
   + BookingRepository.findAllByAccountNameAndStartTimeBetween(...)
   → kombinierte Liste aller belegten Zeiträume

2. Jeden Zeitraum um 5 Minuten puffern (vor + nach dem Termin)

3. Überlappende Zeiträume zusammenführen (merge-Algorithmus)

4. Lücken >= durationMinutes als freie Slots zurückgeben

5. Alle Grenzen auf 15-Minuten-Raster runden

Ausgabe: List<TimeSlot> { start: ISO-String, end: ISO-String }
```

**Unterschied zu homeservice:** Keine Redis-Lookup — der Query geht direkt an MySQL.
Für einen privaten Heim-Server ist das ausreichend schnell.

---

## 5. Auth-Kontext: Wer ist der Ersteller?

homeservice liest `req.user.id` aus dem JWT → numerische User-ID.

Der sync-app-server hat **kein User-Entity**. Auth läuft über Token → `sessions.account_name`.

**Lösung:** Der Controller liest den Token aus dem Header `X-Sync-Token`, schlägt ihn in
`sessions` nach, und verwendet `accountName` als Ersteller-Identifikator.

```kotlin
// Im Controller:
@PostMapping
fun createBooking(
    @RequestHeader("X-Sync-Token") token: String,
    @RequestBody dto: CreateBookingRequest,
): ResponseEntity<BookingResponse> {
    val accountName = sessionRepository.findById(token)
        .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED) }
        .accountName
    return ResponseEntity.ok(bookingService.create(accountName, dto))
}
```

---

## 6. Probleme und Schwierigkeiten

### ⚠️ Problem 1 — Kein User-System

homeservice ordnet Bookings und Messages einem **User** zu (`creatorId`, `userId`).
Der sync-app-server kennt nur `accountName` (String). Nachrichten können keinem
bestimmten Gegenüber zugeordnet werden — es gibt nur einen Account.

**Konsequenz:**
- `creatorId` → `accountName` (String)
- `userId` in Messages → `senderName` (String, frei wählbar, z.B. "Hans")
- Keine Validierung wer eine Message absetzt — jeder mit gültigem Token kann schreiben

**Empfehlung:** Akzeptieren. Der Server ist ein privates Single-Tenant-System.

---

### ⚠️ Problem 2 — Invitee-FK auf contacts.lookup_key

Invitees sind Kontakte aus der `contacts`-Tabelle. Diese sind **accountgebunden** —
ein Kontakt gehört immer zu einem bestimmten `accountName`.

**Risiko:** Ein Booking aus Account A könnte theoretisch Kontakte aus Account B als
Invitees referenzieren, wenn der `lookup_key` bekannt ist.

**Empfehlung:** Beim `addInvitee` prüfen ob der Kontakt zum gleichen `accountName`
gehört wie das Booking. Server-seitig validieren, nicht nur FK-Constraint.

---

### ✅ Problem 3 — `@ManyToMany`, `data class` und `MultipleBagFetchException`

Kotlin `data class` + Hibernate `@ManyToMany` ist problematisch: `data class`
generiert `equals()` und `hashCode()` über alle Felder — das bricht den Hibernate
Lazy-Loading-Proxy.

Zusätzlich: `@ManyToMany` mit `List` (Bag) + weitere `@OneToMany`-Collections würden bei
`JOIN FETCH` eine `MultipleBagFetchException` werfen.

**Lösung (kombiniert):**
- `BookingEntity` als **reguläre Kotlin-Klasse** (kein `data class`), `equals`/`hashCode` nur auf `id`
- `invitees` als `MutableSet<ContactEntity>` (statt `List`) — `Set` verhindert `MultipleBagFetchException`
- Alle Collections `FetchType.LAZY` + `@EntityGraph` im Repository

```kotlin
@Entity
@Table(name = "bookings")
class BookingEntity(
    @Id val id: String,
    // ...
) {
    override fun equals(other: Any?) = other is BookingEntity && id == other.id
    override fun hashCode() = id.hashCode()
}
```

---

### ⚠️ Problem 4 — Hibernate ddl-auto=update und `@ManyToMany`

Hibernate `ddl-auto=update` legt neue Tabellen (`bookings`, `booking_invitees`,
`booking_messages`) automatisch an. Die Join-Tabelle `booking_invitees` wird von
Hibernate **ohne explizite FK-Constraints** angelegt (nur die Spalten).

**Konsequenz:** `ON DELETE CASCADE` auf `contacts.lookup_key` muss manuell per
`ALTER TABLE` nachgetragen werden — oder alternativ über `@JoinTable` und Hibernate
DDL-Konfiguration gesteuert werden. Sonst entstehen verwaiste Invitee-Zeilen wenn
ein Kontakt gelöscht wird.

**Empfehlung:** Nach erstem Start manuell ausführen:
```sql
ALTER TABLE booking_invitees
  ADD CONSTRAINT fk_bi_contact
  FOREIGN KEY (lookup_key) REFERENCES contacts(lookup_key) ON DELETE CASCADE;
```

---

### ⚠️ Problem 5 — Slot-Suche über zwei Tabellen

Der Slot-Algorithmus muss **Appointments** (Android-Sync) und **Bookings** (manuell)
kombinieren. `AppointmentEntity` speichert Zeit in Unix-ms, `BookingEntity` ebenfalls.
Aber `AppointmentEntity` hat kein `account_name`-basiertes `startTime`-Between-Query
in der aktuellen `AppointmentRepository`.

**Lösung:** Neue Repository-Methode hinzufügen:
```kotlin
fun findAllByAccountNameAndDtStartBetween(
    accountName: String, from: Long, to: Long
): List<AppointmentEntity>
```

---

### ⚠️ Problem 6 — `GET /booking/:id/invitees` gibt lookup_keys zurück

homeservice gibt ein Array von User-IDs zurück (`[2, 3]`).
In der Kotlin-Version sind Invitees Kontakte — sinnvoller wäre eine Liste von
`{ lookupKey, displayName }` statt rohen IDs.

**Empfehlung:** Rückgabe als Array von Kontakt-Objekten (oder zumindest `lookupKey` + `displayName`),
nicht nur reine IDs.

---

## 7. Neue Dateien (Übersicht)

```
sync-app-server/src/main/kotlin/de/sync/app/server/
├── data/
│   ├── BookingEntity.kt          (BookingEntity + BookingMessageEntity)
│   └── BookingRepository.kt      (JpaRepository<BookingEntity, String>)
├── BookingController.kt           (alle /booking/... Endpunkte)
└── SlotService.kt                 (Port des TS-Algorithmus)
```

Geändert:
```
data/AppointmentRepository.kt      (neue Methode findAllByAccountNameAndDtStartBetween)
```

---

## 8. Offene Fragen (vor Implementierung klären)

1. **`GET /booking`** — gefiltert nach `accountName` aus Token, oder alle?
2. **`senderName` bei Messages** — frei wählbar im Request-Body, oder immer aus Token (`accountName`)?
3. **Invitees-Response** — nur `lookup_key`-Array, oder vollständiges Kontaktobjekt?
4. **Slot-Suche** — nur Appointments + Bookings, oder auch externe Termine berücksichtigen?
