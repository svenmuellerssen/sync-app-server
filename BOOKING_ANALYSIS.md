# Booking-Modul: Analyse & Implementierungsplan

Dieses Dokument beschreibt den Port des Booking-Systems aus `homeservice` (NestJS/Neo4j/Redis)
in den `sync-app-server` (Spring Boot / Kotlin / Neo4j + Redis).

---

## Status

> Offene Todos → **`CODE_ANALYSIS.md` im Monorepo-Root** (Abschnitt "Booking-Feature", Punkte B1–B5).

| Schritt | Status |
|---|---|
| `BookingNode.kt` + `BookingMessageNode.kt` | ⬜ ausstehend — siehe CODE_ANALYSIS.md #B1 |
| `BookingRepository.kt` | ⬜ ausstehend — siehe CODE_ANALYSIS.md #B2 |
| `BookingController.kt` | ⬜ ausstehend — siehe CODE_ANALYSIS.md #B3 |
| `SlotService.kt` | ⬜ ausstehend — siehe CODE_ANALYSIS.md #B4 |
| `AppointmentRepository` — `dtStartBetween`-Methode | ⬜ ausstehend — siehe CODE_ANALYSIS.md #B5 |

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
- **Datenbank Neo4j:** ✅ Spring Data Neo4j integriert (Dependency + Config + Container)
- **Cache Redis:** ✅ Spring Data Redis integriert (Dependency + Config + Container)
- **Cache:** Redis verfügbar — SlotService kann tagesweise Buckets wie in homeservice cachen
- **Auth:** Token-basiert über `sessions`-Tabelle, kein User-Entity — nur `accountName` (String)
- **Packages:**
  - `graph/` → Neo4j-Repositories (Neo4j) — enthält ContactNode, AppointmentNode, AccountNode u.a.
  - `cache/` → Redis-Repositories (Redis) — enthält SessionEntity (@RedisHash)

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

## 4. Datenmodell (Neo4j)

Booking-Daten werden als Neo4j-Nodes gespeichert — analog zu Contacts und Appointments.

### 4.1 Neo4j-Nodes

```kotlin
// graph/BookingNode.kt
@Node("Booking")
class BookingNode(
    @Id @GeneratedValue val id: Long = -1,
    val accountName: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,        // Unix ms
    val endTime: Long,          // Unix ms
    val locationName: String? = null,
    val createdAt: Long,
    var updatedAt: Long,

    @Relationship(type = "HAS_INVITEE", direction = Relationship.Direction.OUTGOING)
    val invitees: MutableSet<ContactNode> = mutableSetOf(),

    @Relationship(type = "HAS_MESSAGE", direction = Relationship.Direction.OUTGOING)
    val messages: MutableList<BookingMessageNode> = mutableListOf(),
)

// graph/BookingMessageNode.kt
@Node("BookingMessage")
data class BookingMessageNode(
    @Id @GeneratedValue val id: Long = -1,
    val senderName: String,     // accountName oder frei wählbarer Name, da kein User-Entity
    val text: String,
    val createdAt: Long,
)
```

### 4.2 Graphbeziehungen

```
(:Booking { accountName, title, startTime, endTime, ... })
    -[:HAS_INVITEE]->(:Contact { lookupKey, accountName, ... })
    -[:HAS_MESSAGE]->(:BookingMessage { senderName, text, createdAt })
```

**Bedingung — nur eigene Kontakte:** Beim `POST /booking/:id/invitees` wird server-seitig
geprüft, ob der Kontakt (`ContactNode.accountName`) zum gleichen Account wie das Booking gehört.
Fremde Kontakte werden abgelehnt — die Validierung erfolgt explizit im Controller, nicht per Graph-Constraint.

**Invitee-Löschen:** Wird ein Kontakt aus dem System entfernt, muss der `HAS_INVITEE`-Link
separat gelöscht werden (kein automatisches Cascading wie bei SQL FK ON DELETE CASCADE).

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
├── graph/                         ✅ enthält ContactNode, AppointmentNode, AccountNode u.a.
│   ├── BookingNode.kt             ⬜ BookingNode (@Node) + BookingMessageNode              → CODE_ANALYSIS.md #B1
│   └── BookingRepository.kt       ⬜ Neo4jRepository<BookingNode, String>                  → CODE_ANALYSIS.md #B2
├── cache/                         ✅ SessionEntity (@RedisHash) implementiert
├── BookingController.kt           ⬜ alle /booking/... Endpunkte                            → CODE_ANALYSIS.md #B3
└── SlotService.kt                 ⬜ Port des TS-Algorithmus (mit Redis-Cache)              → CODE_ANALYSIS.md #B4
```

Geändert:
```
build.gradle.kts                   ✅ spring-boot-starter-data-neo4j + spring-boot-starter-data-redis
application.properties             ✅ spring.neo4j.* + spring.data.redis.*
cluster-nas.bat                    ✅ sync-neo4j + sync-redis Container + NEO4J_*/REDIS_* Env-Vars
graph/AppointmentRepository.kt      ⬜ neue Methode findAllByAccountNameAndDtStartBetween    → CODE_ANALYSIS.md #B5
```

---

## 9. Offene Fragen

1. **`GET /booking`** — gefiltert nach `accountName` aus Token, oder alle?
2. **`senderName` bei Messages** — frei wählbar im Request-Body, oder immer aus Token (`accountName`)?
3. **Invitees-Response** — nur `contact_id`-Array, oder vollständiges Kontaktobjekt (`contactId`, `lookupKey`, `displayName`)?
4. **Slot-Suche** — nur Appointments + Bookings, oder auch externe Termine berücksichtigen?
