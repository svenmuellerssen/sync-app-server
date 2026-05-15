# sync-app-server

Spring Boot Backend für die Sync App. Nimmt Kontakt- und Termin-Backups vom Android-Gerät entgegen, ermöglicht bidirektionale Synchronisierung und speichert alle Daten in Neo4j. Termine werden versioniert — jede Änderung erzeugt einen neuen Versions-Node, der alte bleibt als History erhalten.

---

## Technologie-Stack

| Komponente | Wert |
|---|---|
| Sprache | Kotlin |
| Framework | Spring Boot 3.5.0 |
| Build | Gradle (Kotlin DSL) |
| Java | 21 |
| Datenbank | Neo4j 5 (Graph-Datenbank) |
| Cache / Sessions | Redis 7 |
| Security | Spring Security (CSRF deaktiviert, BCrypt-Passwörter) |

---

## Endpunkte

Alle Endpunkte außer `/auth/login` und `/auth/register` erfordern den Header `X-Sync-Token: <token>`. Der Account wird **ausschließlich aus dem Token** aufgelöst — `accountName` in Query-Parametern oder Request-Bodies wird ignoriert (⚠ siehe Bekannte Probleme).

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/auth/register` | Neuen Account registrieren: `{ username, password }` → `{ token, expiresAt }` |
| `POST` | `/auth/login` | Login: `{ username, password }` → `{ token, expiresAt }` |
| `POST` | `/auth/logout` | Session beenden: Header `X-Sync-Token: <token>` — nur der Token-Inhaber kann seine eigene Session invalidieren |
| `POST` | `/sync/manifest` | Bidirektionaler Sync-Vergleich → `{ contacts: {…}, appointments: {…} }` |
| `POST` | `/contacts` | Kontakt-Batch hochladen → `{ revision, contactsStored }` |
| `GET` | `/contacts` | Alle gespeicherten Kontakte → `{ accountName, contacts: […] }` |
| `GET` | `/contacts/count` | Anzahl gespeicherter Kontakte → `{ accountName, count }` |
| `POST` | `/appointments` | Termin-Batch hochladen → `{ appointmentsStored, skipped, newCalendars: [{serverCalendarId, name, calendarType}] }` |
| `GET` | `/appointments` | Alle aktuellen Termine (nur aktive HAS_APPOINTMENT-Kante) → `{ accountName, appointments: […] }` |
| `GET` | `/appointments/count` | Anzahl aktueller Termine (logisch dedupliziert per `syncId`) → `{ accountName, count }` |
| `GET` | `/appointments/{syncId}/history` | Alle Versionen eines Termins, neueste zuerst → `{ syncId, versions: […] }` |
| `POST` | `/booking` | Booking anlegen: `{ title, description?, startTime, endTime, locationName?, sharedCalendarId }` → `BookingResponse` |
| `GET` | `/booking` | Eigene Bookings des Tokens → `List<BookingResponse>` |
| `GET` | `/booking/{id}` | Einzelnes Booking → `BookingResponse` |
| `PUT` | `/booking/{id}` | Booking aktualisieren → `BookingResponse` |
| `DELETE` | `/booking/{id}` | Booking löschen → `204 No Content` |
| `POST` | `/booking/{id}/invitees` | Invitees hinzufügen: `{ contactIds: […] }` → `{ saved, filtered, invitees }` — `200` oder `207` |
| `DELETE` | `/booking/{id}/invitees/{contactId}` | Invitee entfernen → `204 No Content` |
| `GET` | `/booking/{id}/invitees` | Invitees eines Bookings → `List<InviteeResponse>` |
| `POST` | `/booking/{id}/message` | Chat-Nachricht anlegen: `{ text }` → `BookingMessageResponse` (senderName aus Token) |
| `GET` | `/booking/{id}/chat` | Chat-Verlauf → `List<BookingMessageResponse>` |
| `PUT` | `/booking/{id}/message/{messageId}` | Nachricht editieren (nur Absender): `{ text }` → `BookingMessageResponse` mit `editedAt` |
| `DELETE` | `/booking/{id}/message/{messageId}` | Nachricht löschen: Absender löscht eigene, Booking-Owner löscht jede → `204 No Content` |
| `GET` | `/booking/slots/available?from=&to=&duration=` | Freie Slots → `List<TimeSlot>` |
| `POST` | `/shared-calendar` | Neuen Shared Calendar anlegen: `{ name, color? }` → `SharedCalendarDto` |
| `GET` | `/shared-calendar/list` | Alle Shared Calendars des Tokens → `List<SharedCalendarDto>` |
| `GET` | `/shared-calendar/invite/{calendarId}` | Einladungscode generieren (7 Zeichen, TTL 10 Min) → `{ code, expiresInSeconds, calendarId }` |
| `POST` | `/shared-calendar/join` | Kalender beitreten: `{ inviteCode }` → `SharedCalendarDto` |
| `DELETE` | `/shared-calendar/{calendarId}` | Eigenen Shared Calendar inkl. Bookings, Termine und Invite-Codes löschen → `204` |
| `DELETE` | `/shared-calendar/{calendarId}/leave` | Kalender verlassen (nur für Nicht-Owner) → `204` |
| `GET` | `/calendar` | Alle eigenen CalendarNodes (OWNS_CALENDAR + MEMBER_OF) → `List<PersonalCalendarDto>` |
| `GET` | `/calendar/google` | Google-Kalender-Metadaten mit bekannten Mitgliedern → `List<GoogleCalendarDto>` |

---

### Auth

- Accounts werden in Neo4j als `(:Account {username, passwordHash, createdAt})` gespeichert
- Passwörter werden mit **BCrypt** gehasht
- Sessions werden in **Redis** gespeichert (`@RedisHash("session")`, TTL 24h) — überleben Server-Neustarts, laufen automatisch ab
- `TokenAuthInterceptor` validiert `X-Sync-Token` global für alle Endpunkte außer `/auth/login` und `/auth/register` und setzt `accountName` als Request-Attribut (kein zweiter Redis-Lookup in den Controllern)

---

### POST /sync/manifest

Body: `{ accountName, contacts: [{ syncId, lastUpdatedAt }], appointments: [{ syncId, lastUpdatedAt }], type: "all"|"contacts"|"appointments", confirmedEmpty: Boolean }`

- Server vergleicht die mitgeschickten SyncIds + Timestamps mit dem eigenen Bestand
- Antwort: `{ contacts: { toUpload, toDownload, toUpdate }, appointments: { toUpload, toDownload, toUpdate } }`
- `toUpload` — SyncIds die beim Server fehlen (Client soll hochladen)
- `toDownload` — Nodes die beim Client fehlen (Server schickt vollständige DTOs)
- `toUpdate` — Server hat neuere Version (`lastUpdatedAt` > Client-Timestamp) → Client soll überschreiben
- **G1 — confirmedEmpty-Guard:** Termine werden nur soft-archiviert wenn `confirmedEmpty=true` (App explizit bestätigt, Kalender-Lese-Berechtigung OK und Gerät wirklich leer) **oder** wenn das mitgeschickte Manifest nicht leer ist. Leeres Manifest ohne `confirmedEmpty=true` löst keine destruktiven Aktionen aus
- **G3 — Source-of-truth für persönliche Termine:** Persönliche Termine, die im Manifest des Clients fehlen, werden serverseitig soft-archiviert (außer G1-Guard-Fall: leeres Manifest ohne `confirmedEmpty=true`)
- **G4 — Dedup per Soft-Archive:** Duplikate (gleiche `DedupKey`-Kombination) werden per `removeHasAppointmentEdge` soft-archiviert — kein Hard-Delete, History bleibt erhalten
- **Runtime-Dedup pro Account:** Vor dem Manifest-Diff dedupliziert der Server alle mehrfach aktiven `HAS_APPOINTMENT`-Kanten pro `syncId` des Accounts (Verlierer werden soft-archiviert)
- **G5 — Shared-Calendar-Schutz:** Termine aus Shared Calendars anderer Accounts werden nicht vom eigenen Manifest des Owners archiviert; `findAllCurrentByAccountName` filtert auf `calendarType: LOCAL`
- Shared-Calendar-Termine anderer Mitglieder werden in `toDownload`/`toUpdate` mitgeliefert, damit sie auf allen Mitglieder-Geräten erscheinen
- Der Android-Client nutzt `/sync/manifest` im `CalendarSyncWorker` als ersten Schritt vor Upload/Download.

---

### POST /contacts

Body: `{ accountName, contacts: [ ContactDto… ] }`

- Upsert-Key: `syncId` (UUID, von App generiert, in `ContactsContract.Data` gespeichert). Fallback auf `lookupKey` für ältere App-Versionen ohne `syncId`
- `syncId`-Preload im Upload ist account-gescoped (`findAllBySyncIdIn(accountName, syncIds)`) — verhindert Cross-Account-Kollisionen bei gleichen `syncId`-Werten
- Beim Server-Start entfernt `Neo4jIndexManager` alte UNIQUE-Constraints auf `(:Contact).syncId` und stellt stattdessen einen normalen Index sicher (`contact_syncId`). So bleibt Kontakt-History mit gleicher `syncId` pro Version möglich und Uploads laufen nicht mehr in `ConstraintValidationFailed`
- Logik: existiert der Kontakt mit gleicher `syncId` und `lastUpdatedAt >= incoming` → überspringen. Sonst: alten Node löschen + neuen anlegen
- Keine Versionierung bei Kontakten — es gibt immer genau einen Node pro Kontakt

---

### POST /appointments

Body: `{ accountName, appointments: [ AppointmentDto… ] }`

- `AppointmentDto.syncId` — UUID, von App generiert, in `CalendarContract.ExtendedProperties` gespeichert (Key `de.sync.contacts.uuid`)
- `AppointmentDto.serverCalendarId` — UUID des `CalendarNode` auf dem Server; beim ersten Sync `null` → Server legt CalendarNode per find-or-create an und gibt ihn in `newCalendars` zurück; App schreibt ihn dann in `CalendarContract.Calendars._SYNC_ID`
- **Stale-Overwrite-Schutz:** `dto.lastUpdatedAt < existing.lastUpdatedAt` → Termin wird übersprungen (Upload älter als aktuelle Server-Version)
- **Hash-basierter Skip:** Server berechnet SHA-256 über alle Termin-Felder. Ist der Hash identisch mit dem gespeicherten → Termin wird übersprungen (`skipped++`)
- **Runtime-Deduplizierung pro `syncId`:** Vor jedem Upsert entfernt der Server doppelte aktive `HAS_APPOINTMENT`-Kanten für `(accountName, syncId)` und archiviert die Verlierer-Node(s). Damit werden Alt-Duplikate auch ohne Neustart bereinigt.
- **Versionierung bei Änderung:** Hash unterscheidet sich → alter `HAS_APPOINTMENT`-Edge entfernen → neuen Node speichern → neuen `HAS_APPOINTMENT`-Edge setzen → `(neu)-[:PREVIOUS_VERSION]->(alt)` im Graph
- **Kalender-Verschiebung:** Wenn `serverCalendarId` im DTO von der im aktuell gebundenen `HAS_APPOINTMENT`-Edge abweicht, wird der Edge vom alten CalendarNode entfernt und am neuen gesetzt
- `calendarAccountType = "LOCAL"` → lokaler Gerät-Kalender (wird beim Restore wiederhergestellt, inkl. `calendarColor`)
- `calendarAccountType = "com.google"` → Google-Kalender (gespeichert + in `GoogleCalendarNode` verknüpft, kein Restore)
- `sharedCalendarId` gesetzt → `[:BELONGS_TO_SHARED_CAL]->(:SharedCalendar)` Beziehung wird angelegt
- `sharedEventOwnerAccount` wird in Responses zurückgegeben → Client schließt vom Server geladene Shared-Termine vom erneuten Upload aus
- Response: `{ appointmentsStored, skipped, newCalendars: [{serverCalendarId, name, calendarType}] }`

---

### GET /appointments/{syncId}/history

- Gibt alle Versions-Nodes mit derselben `syncId` zurück, absteigend nach `versionCreatedAt` (neueste zuerst)
- Jeder Eintrag ist ein vollständiger Snapshot des Termins zu dem Zeitpunkt
- Enthält `versionCreatedAt: Long` (Server-Timestamp der Versions-Anlage)
- ⚠ Gibt `200 OK` mit leerer Liste zurück wenn `syncId` unbekannt (kein 404)

---

### /booking und Unterrouten

- Alle Booking-Endpunkte sind auf den Account des `X-Sync-Token` beschränkt
- `sharedCalendarId` ist Pflicht bei Create/Update; der Account muss Mitglied dieses Kalenders sein
- Invitees: nur Kontakte desselben Accounts werden akzeptiert; fremde werden herausgefiltert → `207 Multi-Status`
- `GET /booking/slots/available` kombiniert Appointments + Bookings, puffert jeden belegten Zeitraum um 5 Minuten, merged Überlappungen, rundet auf 15-Minuten-Raster, cached Tages-Buckets in Redis (`slots:<accountName>:<YYYY-MM-DD>`)
- Cache-Invalidierung bei Booking-Create, -Update und -Delete für alle betroffenen Tage
- ⚠ Cache wird **nicht** invalidiert wenn neue Appointments hochgeladen werden (→ Bekannte Probleme)

---

## Termin-Versionierung

### Konzept

Alle Versionen desselben Termins teilen dieselbe `syncId` (stabiler Appointment-Identifier, von der App vergeben). Jeder Versions-Node hat eine eigene `versionId` (UUID, Server-generiert). Im Graph bilden sie eine Kette, die durch `HAS_APPOINTMENT` verankert ist:

```
(:CalendarNode)-[:HAS_APPOINTMENT]->
  (:Appointment {versionId:"v3"}) -[:PREVIOUS_VERSION]->
  (:Appointment {versionId:"v2"}) -[:PREVIOUS_VERSION]->
  (:Appointment {versionId:"v1"})
```

**`HAS_APPOINTMENT` ist der „aktuelle Version"-Pointer** — nur der Node am Ende dieser Kante gilt als aktiv. Soft-Archive = Edge entfernen (Node bleibt, History intakt). Kein `isLatest`-Flag mehr.

### Upload-Logik

1. App lädt `AppointmentDto` mit `serverCalendarId` hoch
2. Server löst CalendarNode per `serverCalendarId` auf (oder find-or-create beim ersten Sync)
3. SHA-256 über alle Felder berechnen
4. **Stale:** `dto.lastUpdatedAt < existing.lastUpdatedAt` → Skip
5. **Hash identisch** → Skip, kein Schreibvorgang
6. **Hash unterschiedlich** (oder kein Eintrag) → alten `HAS_APPOINTMENT`-Edge entfernen → neuen Node speichern → neuen `HAS_APPOINTMENT`-Edge setzen → `linkVersions(new.id, old.id)` (atomar via `@Transactional`)

### Soft-Archivierung

Wenn ein Termin nicht mehr auf dem Telefon ist (erkannt via `/sync/manifest`):
- `removeHasAppointmentEdge(id)` — CalendarNode-Pointer wird entfernt, Node bleibt erhalten
- History-Kette bleibt intakt; `BELONGS_TO_SHARED_CAL`-Beziehungen bleiben erhalten
- Abfrage „aktuelle Termine": `MATCH (:CalendarNode)-[:HAS_APPOINTMENT]->(a) RETURN a`

### Felder auf AppointmentNode (versioning-relevant)

| Feld | Typ | Bedeutung |
|---|---|---|
| `syncId` | String | Stabile Appointment-Identität über alle Versionen |
| `versionId` | String (UUID) | Eindeutige Identität dieses Versions-Nodes |
| `contentHash` | String | SHA-256 aller Felder — leer bei Nodes vor der Migration |
| `calendarId` | String? | Denormalisiert: server-UUID des CalendarNode (für SlotService, History-Queries) |
| `versionCreatedAt` | Long (ms) | Server-Timestamp der Versions-Anlage (für History-Sortierung) |

### Startup-Migration (`DataMigrationService`)

Beim Start prüft `DataMigrationService` (implementiert als `CommandLineRunner`, läuft vor dem ersten HTTP-Request):
1. **Migration 1 — Versioning-Felder Backfill:** Nodes ohne `versionId` erhalten `versionId`, `isLatest=true`, `versionCreatedAt`, `contentHash=''`
2. **Migration 2a — SharedCalendar HAS_APPOINTMENT:** `BELONGS_TO_SHARED_CAL`-Kanten → `HAS_APPOINTMENT` vom SharedCalendarNode
3. **Migration 2b — Personal CalendarNode anlegen:** find-or-create `CalendarNode` per `(calendarName, accountName, calendarType)` → `HAS_APPOINTMENT`
4. **Migration 2c — calendarId Backfill:** Nodes mit `HAS_APPOINTMENT` aber ohne `calendarId`-Feld
5. **Migration 3a — OWNS_CALENDAR:** `createdBy`-Account bekommt `OWNS_CALENDAR`-Kante zum SharedCalendar
6. **Migration 3b — MEMBER_OF:** `HAS_MEMBER`-Kanten von Nicht-Ownern → `MEMBER_OF`
7. **Migration 3c — Redundante Owner-HAS_MEMBER entfernen**

Alle Migrationen sind idempotent (MERGE + WHERE IS NULL Patterns) — sicher bei Mehrfach-Start.

---

## Kalender-Konzepte

### Personal Calendars (CalendarNode)

Persönliche Kalender-Nodes werden beim ersten Sync automatisch angelegt (find-or-create per `calendarName + accountName + calendarType`):

```
(:Account)-[:OWNS_CALENDAR]->(:CalendarNode {
    calendarId: UUID,
    name: String,
    color: Int?,
    calendarType: "LOCAL"|"SHARED"|"GOOGLE",
    accountName: String,
    deletedAt: Long?   ← null = aktiv
})
   -[:HAS_APPOINTMENT]->(:Appointment)
```

- `GET /calendar` gibt alle CalendarNodes zurück, auf die der Account via `OWNS_CALENDAR` oder `MEMBER_OF` zugreift
- App schreibt `calendarId` in `CalendarContract.Calendars._SYNC_ID` → folgende Uploads nutzen direkt die ID

### Shared Calendars (Server-verwaltet)

Kalender die auf dem Server angelegt und per Invite-Code geteilt werden:
- Mitglieder treten per 7-stelligem Code bei (Redis, TTL 10 Min, einmalig nutzbar)
- ⚠ Alle Mitglieder können Invite-Codes generieren, nicht nur der Owner (By Design oder offene Frage — dokumentiert unter Bekannte Probleme)
- Owner löscht den Shared Calendar per `DELETE /shared-calendar/{calendarId}` inkl. Bookings, Termine (alle Versionen) und Invite-Codes
- Nicht-Owner verlassen den Kalender per `DELETE /shared-calendar/{calendarId}/leave`; Owner erhält `409 Conflict` auf diesen Endpunkt
- Events erscheinen auf Mitglieder-Geräten als lokaler `LOCAL`-Kalender in der Android-Kalender-App
- Neo4j: `(:Account)-[:OWNS_CALENDAR]->(:SharedCalendar)` (Ersteller), `(:Account)-[:MEMBER_OF]->(:SharedCalendar)` (Mitglieder), Events: `(:Appointment)-[:BELONGS_TO_SHARED_CAL]->(:SharedCalendar)` + `(:SharedCalendar)-[:HAS_APPOINTMENT]->(:Appointment)`

### Google Shared Calendar Tracking

Bestehende geteilte Google-Kalender werden getracked:
- Events werden hochgeladen (für Slot-Blocking und Backup), kein Download (Google übernimmt das)
- Bekannte Kontakte die am Kalender beteiligt sind werden als `[:HAS_MEMBER]->(:Contact)` gespeichert
- `accessLevel >= 500` → Booking auf diesem Kalender erlaubt
- Neo4j: `(:GoogleCalendar {calendarId, displayName, calendarAccountName, color, accessLevel})-[:HAS_MEMBER]->(:Contact)`

---

## Datenmodell (Neo4j)

```
(:Account {username, passwordHash, createdAt})

(:Contact {syncId, lookupKey, accountName, displayName, givenName, familyName,
           middleName, namePrefix, nameSuffix, phoneticGivenName,
           phoneticMiddleName, phoneticFamilyName, notes,
           lastUpdatedAt, createdAt})
  -[:HAS_PHONE]->           (:PhoneNumber      {number, type, label})
  -[:HAS_EMAIL]->           (:Email            {address, type, label})
  -[:HAS_ADDRESS]->         (:PostalAddress    {street, city, region, postCode, country, type, label})
  -[:HAS_ORG]->             (:Organization     {company, title, department})
  -[:HAS_IM]->              (:InstantMessenger {handle, protocol, customProtocol})

(:Appointment {syncId, versionId, contentHash, versionCreatedAt,
               accountName, calendarId,
               title, description, dtStart, dtEnd,
               duration, allDay, timezone, rrule, location, organizer,
               calendarName, calendarAccountType, calendarAccountName,
               calendarColor, status, lastUpdatedAt, createdAt})
  -[:HAS_ATTENDEE]->          (:Attendee {name, email, type, status} -[:IS_CONTACT]->(:Contact))
  -[:HAS_REMINDER]->          (:Reminder {minutes, method})
  -[:BELONGS_TO_SHARED_CAL]-> (:SharedCalendar)  ← wenn sharedCalendarId gesetzt
  -[:BELONGS_TO_GOOGLE_CAL]-> (:GoogleCalendar)  ← wenn calendarAccountType="com.google"
  -[:PREVIOUS_VERSION]->      (:Appointment)     ← vorherige Version (History-Chain)

(:CalendarNode {calendarId, name, color, calendarType, accountName, deletedAt})
  ← verbunden via (:Account)-[:OWNS_CALENDAR]->(:CalendarNode)
  -[:HAS_APPOINTMENT]->(:Appointment)            ← "aktuelle Version"-Pointer

(:Booking {accountName, title, description, startTime, endTime,
           locationName, createdAt, updatedAt})
  -[:HAS_INVITEE]->       (:Contact)
  -[:HAS_MESSAGE]->       (:BookingMessage {messageId, senderName, text, createdAt, editedAt?})
  -[:BELONGS_TO_SHARED_CAL]-> (:SharedCalendar)

(:SharedCalendar {calendarId, name, color, createdAt, createdBy, deletedAt?})
  ← (:Account)-[:OWNS_CALENDAR]->(:SharedCalendar)   (Ersteller)
  ← (:Account)-[:MEMBER_OF {joinedAt}]->(:SharedCalendar)  (Mitglieder)
  -[:HAS_APPOINTMENT]->(:Appointment)                 ← aktuelle Shared-Termine

(:GoogleCalendar {calendarId, displayName, calendarAccountName, color, accessLevel, accountName})
  -[:HAS_MEMBER]-> (:Contact)

  ── Redis ──
(:Session {token, accountName, ttlSeconds})              TTL 24h
(:SharedCalendarInvite {inviteCode, calendarId, createdBy})  TTL 10 Min, einmalig
```

**Hinweis `accessLevel`:** Das Feld gehört zum `GoogleCalendarNode`, nicht zum `AppointmentNode`. Es bestimmt ob der Account Bookings auf diesem Google-Kalender anlegen darf (>= 500 = CONTRIBUTOR).

**Attendee-Typ-Werte (Android CalendarContract):**
- `type`: 0=NONE, 1=REQUIRED, 2=OPTIONAL, 3=RESOURCE
- `status`: 0=NONE, 1=ACCEPTED, 2=DECLINED, 3=INVITED, 4=TENTATIVE

---

## Architektur-Komponenten

| Datei | Rolle |
|---|---|
| `AuthController.kt` | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout` |
| `ContactsController.kt` | `POST/GET /contacts`, `GET /contacts/count` — Upsert per syncId/lookupKey |
| `AppointmentsController.kt` | `POST/GET /appointments`, `GET /count`, `GET /{syncId}/history` — delegiert an AppointmentService |
| `AppointmentService.kt` | Atomares HAS_APPOINTMENT-Swap, CalendarNode find-or-create, Stale-Schutz, Hash-Dedup, Slot-Cache-Invalidierung |
| `BookingController.kt` | 12 Endpunkte: CRUD Bookings, Invitees, Chat, Slot-Suche |
| `SharedCalendarController.kt` | Shared Calendars (OWNS_CALENDAR/MEMBER_OF), Invite-Codes, Google-Kalender-Metadaten, `GET /calendar` |
| `SyncController.kt` | `POST /sync/manifest` — bidirektionaler Delta-Abgleich (G1-G5 gefixed) |
| `SlotService.kt` | Freie Slots aus Appointments + Bookings, 5-Min-Puffer, 15-Min-Raster, Redis-Tagescache |
| `TokenAuthInterceptor.kt` | Validiert `X-Sync-Token` global (außer `/auth/login` und `/auth/register`), setzt `accountName` als Request-Attribut |
| `GlobalExceptionHandler.kt` | `@RestControllerAdvice` — strukturierte Fehler-Responses (`VALIDATION_ERROR`, `BAD_REQUEST`, `INTERNAL_ERROR`) ohne Stack-Trace-Leak; behandelt auch `HttpMessageNotReadableException` (fehlende/fehlerhafte JSON-Felder → 400) |
| `DataMigrationService.kt` | `CommandLineRunner` — 7 idempotente Startup-Migrationen (Versioning-Felder, CalendarNode+HAS_APPOINTMENT, OWNS_CALENDAR/MEMBER_OF) |
| `DataConfig.kt` | Explizites Repository-Scanning — trennt Neo4j (`graph/`) und Redis (`cache/`) Repositories |
| `RedisConfig.kt` | `jsonRedisTemplate` (Jackson) + `StringRedisTemplate` |
| `SecurityConfig.kt` | CSRF deaktiviert, alle Requests permitted (Auth via Interceptor) |

---

## Bekannte Probleme & Offene Todos

### ✅ Behobene Probleme

| # | Problem | Fix |
|---|---|---|
| G1 | Leeres Manifest archiviert alle Server-Termine (stiller Datenverlust) | `confirmedEmpty`-Guard in `SyncController` |
| G2 | Slot-Cache nicht invalidiert bei Appointment-Upload | `slotService.invalidateRange()` in `AppointmentService` |
| G3 | Manifest-Rekonsiliation für persönliche Termine fehlte/war inkonsistent | Persönliche Termine, die im Client-Manifest fehlen, werden über `archiveOrphanedPersonalAppointments` soft-archiviert (mit G1-Guard) |
| G4 | Dedup macht Hard-Delete (zerstört History-Chain) | Dedup verwendet `removeHasAppointmentEdge` (Soft-Archive) |
| G5 | Shared-Calendar-Termine werden vom Owner-Manifest archiviert | `findAllCurrentByAccountName` filtert `calendarType: LOCAL`; SharedCal-Termine separat behandelt |
| G6 | Race Condition Doppelbuchung bei Slot-Buchung | Overlap-Check mit `@Transactional` + `findAllOverlappingRange()` in `createBooking` und `updateBooking` |
| G7 | Anna-Bob-Problem: SharedCalendar-Members sahen veraltete Slots | `findMemberUsernamesByCalendarIds` + Cache-Invalidierung in `AppointmentService` und `BookingController` |
| G8 | `redis.keys("slots:*")` war O(N) über alle Keys | SCAN-Cursor in `invalidateAccount()` |
| G10 | Contact-Upload: Hard-Delete zerstörte History und externe Kanten (z. B. HAS_INVITEE) | In-place-Update via `id = existing?.id` in `ContactNode`-Konstruktor — kein `deleteById()` mehr |

---

### 🔴 Kritisch

- [x] **Contact-Upload: Hard-Delete statt Soft-Delete** (`ContactsController.uploadContacts`) ✅  
  `deleteById()` zerstört History und Referenzen dauerhaft. Fix: `deletedAt = now()` setzen statt physisch löschen.

- [x] **Contact-Queries ohne Soft-Delete-Filter** (`ContactRepository`) ✅  
  `findAllByAccountName()` und `countByAccountName()` filtern nicht nach `deletedAt IS NULL` — gelöschte Kontakte erscheinen in GET-Antworten und im Sync-Manifest.

- [x] **Gelöschte Kontakte in `toDownload`** (`SyncController.buildContactManifest`) ✅  
  `findAllByAccountName()` ohne `deletedAt`-Filter — Phone wird aufgefordert, soft-deleted Kontakte zu synchen.

- [x] **N+1 Query im Contact-Upload** (`ContactsController.uploadContacts`) ✅  
  Pro Kontakt ein einzelner `findBySyncId()`-DB-Hit. Bei 1000 Kontakten = 1000 Queries. Fix: account-gescopter Batch-Lookup `findAllBySyncIdIn(accountName, syncIds)` + Map.

- [x] **`senderName` nicht validiert → Impersonation** (`BookingController.createMessage`) ✅  
  Chat-Messages lesen `senderName` aus dem Request-Body. Jeder kann Nachrichten unter fremdem Namen schreiben. Fix: `senderName = accountName` aus Token setzen.

- [x] **Message-Delete ohne Ownership-Prüfung** (`BookingController.deleteMessage`) ✅  
  Prüft nur ob das Booking existiert, nicht ob der aktuelle User die Nachricht erstellt hat. Fix: `senderName == accountName`-Prüfung vor dem Löschen.

- [x] **SharedCalendar-Löschung → verwaiste Bookings** (`SharedCalendarRepository`) ✅  
  Beim Löschen eines SharedCalendars werden alle aktiven Bookings kaskadierend soft-deleted (`softDeleteAllBySharedCalendarId`). Reihenfolge: Mitglieder snapshotten → Kalender soft-löschen → Bookings soft-löschen → Cache aller Mitglieder invalidieren. Defense-in-depth: `findAllOverlappingRange` und `requireBooking` ignorieren Bookings mit gelöschtem SharedCalendar.

- [x] **Kontakt-Löschung → Phantom-Referenzen in Invitees** (`BookingController`, `ContactNode`) ✅  
  `HAS_INVITEE`-Edges bleiben bestehen wenn ein ContactNode soft-deleted wird. Fix: `addInvitees` nutzt `findAllActiveByAccountNameAndLookupKeyIn` (filtert `deletedAt IS NULL`); `listInvitees`, `addInvitees`-Response und `toResponse()` filtern gelöschte Invitees in der Applikationsschicht heraus.

---

### 🟠 Wichtig

**Soft-Delete Lücken**
- [x] `SyncController.buildAppointmentManifest`: Appointments aus gelöschten SharedCalendars werden noch ausgeliefert ✅  
  `findAllCurrentSharedByAccountName` und `findAllCurrentBySharedCalendarIds` filtern jetzt `sc.deletedAt IS NULL`.
- [x] `CalendarRepository.findByCalendarId`: kein `deletedAt IS NULL`-Filter ✅  
  Auf `@Query` mit `WHERE cal.deletedAt IS NULL` umgestellt.
- [x] `SlotService.findAvailableSlots`: soft-archivierte Appointments blockieren noch Slots ✅  
  `AppointmentRepository.findAllOverlappingRange` prüft jetzt `n.deletedAt IS NULL` für beide SharedCalendar-Branches. `countAllCurrentByAccountName` zählt logisch dedupliziert über `syncId` (Fallback `id(a)` wenn `syncId` fehlt).
- [x] `BookingController.addInvitees`: soft-deleted Kontakte können als Invitee gespeichert werden ✅  
  Bereits in vorherigem Schritt gefixt (`findAllActiveByAccountNameAndLookupKeyIn`).

**Sicherheit & Ownership**
- [x] `SharedCalendarController.generateInvite`: nur noch Owner darf Invite-Codes erzeugen (Members ausgeschlossen)
- [x] `SharedCalendarController.joinSharedCalendar`: Duplikat-Check war bereits vorhanden (`members.none { ... }`) — kein Bug
- [x] Mehrere Endpunkte lesen `accountName` aus Query/Body statt aus dem Token — bereits durch `TokenAuthInterceptor` / `resolveAccount()` abgedeckt; kein zusätzlicher Fix nötig
- [x] `BookingController.listBookings`: gibt jetzt eigene Bookings + Bookings in zugänglichen SharedCalendars zurück (`findAllVisibleIdsByAccountName`); `requireBooking` unterscheidet jetzt Lese- (`requireOwnership=false`) und Schreib-Zugriff (`requireOwnership=true`)

**Cache-Invalidierung**
- [x] `ContactsController.uploadContacts`: `slotService.invalidateAccount(accountName)` am Ende ergänzt
- [x] `SharedCalendarController.leaveSharedCalendar`: invalidiert jetzt alle betroffenen Accounts (Austretender + verbleibende Members + Owner)
- [ ] `AppointmentService`: Google-Calendar-Member-Sync invalidiert Slot-Cache neu hinzugekommener Members nicht — deferred (ContactNode ≠ AccountNode, kein direkter Link; Aufwand übersteigt Nutzen für Single-User-Server)

**Sync-Logik**
- [x] ~~Rekonsiliation: `AppointmentService.processBatch` ruft am Ende `archiveOrphanedPersonalAppointments` auf~~ — **entfernt (BUG-A)**. Archivierung bei Manifest-basiertem Sync obliegt ausschließlich `SyncController.buildAppointmentManifest`.
- [x] `AppointmentService.resolveOrCreateCalendarNode`: Race-Condition bei gleichzeitigem ersten Sync zweier Phones — fix via Neo4j `MERGE` auf (name, accountName, calendarType) statt find-then-create; `ON MATCH SET deletedAt = null` reaktiviert ggf. soft-gelöschte Kalender gleichen Namens
- [x] `buildContactManifest`: bereits korrekt implementiert — `local.lastUpdatedAt > node.lastUpdatedAt`-Zweig in `toUpload` vorhanden

**Booking**
- [x] N+1 Query in `addInvitees()`: bereits als Batch-Query implementiert (`findAllActiveByAccountNameAndLookupKeyIn`)
- [x] `endTime == null → 0-Dauer-Appointment`: `SlotService.endTime()` nutzt jetzt `dtStart + 15min` als Fallback; `findAllOverlappingRange` Cypher nutzt `coalesce(a.dtEnd, a.dtStart + 900000)`
- [ ] Fehlende Felder in `BookingNode`: `status` (PENDING/CONFIRMED/CANCELLED), `cancelledAt` für Soft-Delete, `bookingId` (UUID)
- [x] `removeInvitee()`: wirft bereits `ResponseStatusException(NOT_FOUND)` wenn Invitee nicht gefunden
- [ ] Slot-Cache Timezone-Bug: Cache-Keys rechnen UTC — User in CET/CEST bekommen falsch gecachte Ergebnisse
- [ ] `AppointmentBatchRequest` / `ContactBatchRequest`: kein `@Size`-Limit — theoretisch unbegrenzte Payloads möglich (OOM-Risiko)

**Hinweis — Phantom-Versionen bei Legacy-Nodes**  
Alle vor der Versioning-Migration gespeicherten Appointments haben `contentHash = ''`. Beim ersten Re-Upload entsteht immer eine neue Version (weil `'' != realHash`), auch ohne inhaltliche Änderung. Einmaliger Artefakt pro Termin — akzeptiert.

---

### 🟡 Minor / Optimierung

- [ ] `BackupResponse.revision`: zufällige UUID, wird nirgends genutzt oder verglichen — entweder `MAX(lastUpdatedAt)` zurückgeben oder Feld entfernen
- [ ] `BookingController.requireBooking`: unterscheidet nicht zwischen "nicht gefunden" und "gehört anderem Account" — Client sieht immer denselben Fehler
- [ ] Kein globaler `@RestControllerAdvice` → kein einheitliches Error-Response-Format, Stack-Traces in Logs
- [ ] `GET /appointments/{syncId}/history` gibt `200 OK` bei unbekannter `syncId` statt `404`
- [ ] History-Endpoint filtert `accountName` in Kotlin statt Cypher — ineffizient bei langen Historien; Fix: `WHERE a.accountName = $accountName` in `@Query`
- [ ] `accountName` im Request-Body wird serverseitig ignoriert (Token-Account wird verwendet) — verwirrend; als Breaking Change aus DTOs entfernen
- [ ] Keine Pagination: `GET /contacts`, `GET /appointments`, `listBookings()`, `listInvitees()`, `getChat()` geben unbegrenzte Result-Sets zurück
- [ ] `deleteCalendarGraph` löscht gesamte Versionshistorie bei SharedCalendar-Löschung (alle `isLatest=false`-Nodes mitgelöscht)
- [ ] `findAllOverlappingRange` macht zwei separate DB-Hits pro Tag (Appointments + Bookings) — kombinierbar
- [ ] Fehlende Paginierung in `buildAppointmentManifest` bei Accounts mit sehr vielen SharedCalendars
- [ ] `maxByOrNull { it.id }` nach Nachrichten-Insert findet falsche Message bei concurrent Inserts — direkt neu erstellten Node zurückgeben
- [ ] Keine Max-Größe für Invitee-Liste — `require(contactIds.size <= 100)` ergänzen
- [ ] Redundanter `@RequestHeader("X-Sync-Token")` im Slots-Endpunkt — Token wird vom Interceptor bereits validiert
- [ ] Slot-Algorithmus Edge-Case: Appointment kurz nach Range-Ende mit Padding blockiert letzten Slot nicht korrekt — busy-Intervalle auf Query-Range clippen
- [x] `generateInvite`: nur noch Owner darf Invite-Codes erzeugen (bereits in Sicherheit & Ownership gefixt)

---

## Konfiguration

Alle Werte via Umgebungsvariablen (mit Defaults für lokale Entwicklung):

| Variable | Default | Beschreibung |
|---|---|---|
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt-URL |
| `NEO4J_USER` | `neo4j` | Neo4j Benutzer |
| `NEO4J_PASSWORD` | `neo4jsecret` | Neo4j Passwort |
| `REDIS_HOST` | `localhost` | Redis Host |
| `REDIS_PORT` | `6379` | Redis Port |
| `REDIS_PASSWORD` | *(leer)* | Redis Passwort |

---

## Build

**Voraussetzung:** Java 21

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
.\gradlew.bat bootJar -x test
```

JAR liegt unter: `build/libs/sync-app-server-0.0.1-SNAPSHOT.jar`

---

## Tests

Alle Tests liegen unter `src/test/kotlin/de/sync/app/server/`.  
Vollständiges Test-Katalog und Bug-Tracking: `sync-app-server/todo-tests.md`

**Ausführen:**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
.\gradlew.bat test
```

> Testcontainers startet automatisch Neo4j 5 und Redis 7 — Docker muss laufen.

---

### Teststrategie

| Typ | Werkzeug | Einsatz |
|---|---|---|
| **Integrationstest** (`@SpringBootTest`) | Testcontainers (Neo4j 5 + Redis 7) | Service-Logik, Repository-Queries, Controller-Routing mit echter DB |
| **Controller-Test** (`@WebMvcTest`) | MockMvc + Mockito | HTTP-Routing, Validierung, Fehler-Status-Codes — Repositories gemockt |
| **Unit-Test** | JUnit 5 + Mockito | Pure Berechnungen ohne Spring-Kontext (Slot-Algorithmus, Hash) |

**Faustregel:** Integrationstest ist der Default. Controller-Test nur für die HTTP-Schicht. Unit-Test nur für reine Funktionen.

---

### Testdateien und Abdeckung

| Datei | Typ | Abgedeckte Bereiche |
|---|---|---|
| `AppointmentServiceIntegrationTest.kt` | Integration | AS1–AS15: Stale-Schutz, Hash-Dedup, Versionierung, SharedCal, CalendarNode-Bootstrap, Soft-Archivierung, Delta-Upload (BUG-1 red) |
| `SyncControllerIntegrationTest.kt` | Integration | SC1–SC9: Manifest-Logik, `confirmedEmpty`-Guard, SharedCal-Termine, Kontakt-Sync, Cache-Invalidierung |
| `BookingControllerTest.kt` | Controller + Integration | BC-v1–BC-v7 (Validierung), BCi1–BCi5 (Overlap-Guard, Member-Access, Invitees, SharedCal-Delete) |
| `ContactsControllerTest.kt` | Integration | CC1–CC7: Upsert, Stale-Skip, History-Chain, Account-Isolation (BUG-2 red) |
| `SharedCalendarControllerTest.kt` | Controller + Integration | SCS-v1–SCS-v4 (Auth/Ownership), SCSi1–SCSi5 (Invite-Code, Leave, Delete, Cache) |
| `AuthIntegrationTest.kt` | Integration | Auth1–Auth5: Login, Register, Logout, Token-Ablauf |
| `AppointmentsControllerTest.kt` | Controller | AC1–AC3: Token-Validierung, History-Isolation, Count-Endpoint |
| `SlotServiceTest.kt` | Unit | SlotS1–SlotS4: Booking-Block, 5-Min-Padding, ungültige Duration (BUG-5 ✅), Tagesgrenzen |
| `HashAppointmentTest.kt` | Unit | SHA-256-Hash — Vollständigkeit und Determinismus |
| `TokenAuthInterceptorTest.kt` | Unit | Token-Validierung, fehlender Header, abgelaufene Session |

---

### Bekannte Bugs — Teststatus

Bugs mit Status **„Test schlägt fehl"** sind absichtlich rote Tests — sie dienen als Regressionsschutz sobald der Bug gefixt wird.

| ID | Bug | Schwere | Teststatus |
|---|---|---|---|
| ~~BUG-A~~ | ~~`archiveOrphanedPersonalAppointments` in `processBatch` erhält nur Upload-Delta → archiviert alle vorhandenen Termine~~ | 🔴 Kritisch | **AS11, AS14** ✅ gefixt |
| ~~BUG-B~~ | ~~`findCurrentOrArchivedBySyncId` ohne LIMIT → `IncorrectResultSizeDataAccessException` bei Duplikaten → `UnexpectedRollbackException` → Sync bricht nach ~900 Terminen ab~~ | 🔴 Kritisch | **AS16** ✅ gefixt |
| BUG-1 | `archiveOrphanedPersonalAppointments` in `processBatch` erhält nur Upload-Delta → archiviert alle vorhandenen Termine | 🔴 Kritisch | **AS14** ✅ gefixt (= BUG-A) |
| BUG-2 | `findByLookupKey()` ohne `accountName`-Filter → gibt Kontakt aus beliebigem Account zurück | 🔴 Hoch | **CC4** — Test schlägt fehl bis Bug gefixt |
| BUG-3 | Invite-Code kann mehrfach genutzt werden (nicht-atomares Join) | 🟠 Mittel | **SCSi1/SCSi2** — sequenziell nachgewiesen |
| BUG-4 | `removeHasAppointmentEdge()` läuft vor `save()` — Termin orphaned bei Save-Fehler | 🟠 Mittel | kein Test (nicht reproduzierbar ohne erzwungenen Fehler) |
| BUG-5 | `SlotService`: ungültige Duration-Strings wurden silently auf 15 min zurückgerechnet | 🟡 Niedrig | **SlotS3** ✅ gefixt + grüner Test |
| BUG-6 | `DedupKey` in `SyncController` dedupliziert wiederkehrende Termin-Instanzen falsch | 🟡 Niedrig | kein Test (Edge-Case mit komplexen Seriendaten) |
| BUG-7 | Race Condition bei `register()` ohne Unique-Constraint auf `Account.username` | 🟠 Mittel | kein Test (Race nicht deterministisch testbar) |

---

### Test-Setup (Vorlage für Integrationstests)

```kotlin
@SpringBootTest
@Testcontainers
class MyIntegrationTest {

    @Autowired lateinit var driver: Driver
    @Autowired lateinit var stringRedisTemplate: StringRedisTemplate

    @BeforeEach
    fun setup() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
        stringRedisTemplate.keys("slots:*").takeIf { it?.isNotEmpty() == true }
            ?.let { stringRedisTemplate.delete(it) }
    }

    companion object {
        @Container @JvmStatic
        val neo4j: Neo4jContainer<*> = Neo4jContainer("neo4j:5").withoutAuthentication()

        @Container @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.neo4j.uri") { neo4j.boltUrl }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379).toString() }
        }
    }
}
```

- **Controller-Tests:** `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)` + Mockito für Repositories
- **Token simulieren:** `request.setAttribute("accountName", ...)` via `EndpointTestSupport.authenticated()`
- **Lenient-Mocks:** `@MockitoSettings(strictness = Strictness.LENIENT)` nötig wenn `@BeforeEach`-Stubs in einzelnen Tests überschrieben werden

---

## Docker

```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## Deployment auf der NAS

Skript: `cluster-nas.bat` (liegt im Root des Monorepos `sync-app/`)

```bat
# Alles bauen (Server + Android APK), Images pushen, auf NAS deployen, APK auf Telefon installieren:
cluster-nas.bat deploy build

# Nur Container neu starten (Images müssen bereits in der Registry sein):
cluster-nas.bat deploy

# Nur Android APK auf verbundenem Telefon installieren:
cluster-nas.bat phone
cluster-nas.bat phone build   # zuerst neu bauen

# Status / Logs:
cluster-nas.bat status
```

**Ports auf der NAS:**
- Neo4j: `7474` (Browser UI), `7687` (Bolt)
- Redis: `6379`
- API: `8083`

**NAS Registry:** `192.168.2.223:32768`  
**Image:** `sync-app-server:latest`

---


---

## Ideen & Zukünftige Features

### 💡 Booking-Bestätigungs-Workflow (Status-Maschine)
Ein Booking könnte einen Status-Workflow durchlaufen:
- PENDING — Buchungsanfrage erstellt, wartet auf Bestätigung durch alle Teilnehmer
- CONFIRMED — Alle Teilnehmer haben bestätigt
- CANCELLED — Storniert (Soft-Delete), bleibt im Graph erhalten

Invitees könnten einzeln bestätigen (ACCEPTED/DECLINED/PENDING), ähnlich wie bei Kalender-Einladungen.

### 💡 Display-Name im Chat aus Kontakten
senderName in Chat-Nachrichten wird immer als ccountName (Token) gespeichert.  
Die App könnte beim Anzeigen den ccountName des Absenders gegen die lokalen Kontakte matchen  
(z.B. per E-Mail oder gespeichertem Server-Nutzernamen) und den Namen anzeigen,  
den der Empfänger für diese Person in seinen Kontakten gespeichert hat.  
Ähnlich wie WhatsApp: Der Absender-Account ist fix, der angezeigte Name kommt aus deinen Kontakten.
