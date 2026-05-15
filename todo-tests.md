# Todo-Tests: sync-app-server (Spring Boot)

Stand: 2026-05-02

---

## Teststrategie

| Typ | Wann | Werkzeug |
|---|---|---|
| **Integrationstest** (`@SpringBootTest`) | Alles was Service-Logik, Repository-Queries oder Controller-Routing mit echter DB testet | Testcontainers (Neo4j 5 + Redis 7) |
| **Controller-Test** (`@WebMvcTest`) | HTTP-Routing, Request-Validierung, Fehlerbehandlung — Repositories gemockt | MockMvc + Mockito |
| **Unit Test** | Pure Funktionen ohne Kontext: `hashAppointment`, Slot-Kalkulationen | JUnit 5 + Mockito |

**Faustregel:** Integrationstest ist der Default. Controller-Test nur für HTTP-Schicht (Status-Codes, Header, Validierung). Unit-Test für pure Berechnungen ohne Spring-Kontext.

---

## Bekannte Bugs (als Tests nachzuweisen + ggf. zu fixen)

| ID | Bug | Schwere | Fundort | Status |
|---|---|---|---|---|
| BUG-1 | `archiveOrphanedPersonalAppointments` in `processBatch` erhält nur Upload-Delta, archiviert dadurch alle vorhandenen Appointments | 🔴 KRITISCH | `AppointmentService.processBatch()` L70 | offen |
| BUG-2 | `ContactRepository.findByLookupKey()` ohne `accountName`-Filter — gibt Kontakt aus beliebigem Account zurück | 🔴 HOCH | `ContactRepository.kt` L10, `AppointmentService.processSingle()` L122 | offen |
| BUG-3 | Invite-Code kann mehrfach genutzt werden (nicht-atomares Join) | 🟠 MITTEL | `SharedCalendarController.joinSharedCalendar()` L112–128 | offen |
| BUG-4 | `AppointmentService.processSingle()`: `removeHasAppointmentEdge()` läuft vor `save()` — bei Save-Fehler ist Appointment orphaned | 🟠 MITTEL | `AppointmentService.processSingle()` L131–167 | offen |
| BUG-5 | `SlotService.endTime()`: fehlerhafte Duration-Strings fallen auf 15 min zurück (statt Termin-Dauer) | 🟡 NIEDRIG | `SlotService.endTime()` (private) | ✅ behoben |
| BUG-6 | `DedupKey` in `SyncController` kann wiederkehrende Termin-Instanzen falsch deduplizieren | 🟡 NIEDRIG | `SyncController.buildAppointmentManifest()` L92–99 | offen |
| BUG-7 | `AuthController.register()`: Race Condition ohne Unique-Constraint auf `Account.username` | 🟠 MITTEL | `AuthController.register()` | offen |

---

## 1. AppointmentService — Integrationstests

**Datei:** `AppointmentServiceIntegrationTest.kt` ✅ (AS1–AS13 vorhanden)  
**Neue Tests hinzufügen:**

| # | Test | Erwartung | Status |
|---|---|---|---|
| AS1 | Stale-Upload (älteres `lastUpdatedAt`) wird übersprungen | `skipped=1`, V1 bleibt aktiv | ✅ |
| AS2 | Gleichen Termin zweimal hochladen → kein neuer Node | `skipped=1`, nur 1 Node | ✅ |
| AS3 | Archivierter Termin mit gleichem Content → un-archiviert, kein neuer Node | `stored=1`, `deletedAt=null`, 1 Node | ✅ |
| AS4 | Neuer Termin → 1 Node, keine PREVIOUS_VERSION-Kante | `stored=1`, Kanten=0 | ✅ |
| AS5 | Inhaltsänderung → neuer Node + PREVIOUS_VERSION-Kante | 2 Nodes, 1 Kante, V2 aktiv | ✅ |
| AS6 | SharedCalendar-Owner kann Termin hochladen | `stored=1` | ✅ |
| AS7 | Account ohne Mitgliedschaft wird rejected | `skipped=1` | ✅ |
| AS8 | Unbekannte `serverCalendarId` → skipped | `skipped=1` | ✅ |
| AS9 | Erster Sync ohne `serverCalendarId` → CalendarNode neu erstellt | `newCalendars.size=1` | ✅ |
| AS10 | Zweiter Bootstrap-Upload → existierender CalendarNode wiederverwendet | `newCalendars` leer, 1 CalendarNode | ✅ |
| AS11 | Termin fehlt im (Voll-)Batch → soft-archiviert | `deletedAt!=null` | ✅ |
| AS12 | Slot-Cache des Accounts nach Batch gelöscht | Redis-Key weg | ✅ |
| AS13 | Slot-Cache aller SharedCalendar-Mitglieder nach Batch gelöscht | Bob's Redis-Key weg | ✅ |
| AS14 | **BUG-1**: Delta-Upload (nur neue Termine) darf existierende Termine NICHT archivieren | Beide Termine aktiv nach `processBatch([new])` | ✅ (schlägt fehl bis BUG-1 gefixt) |
| AS15 | Un-Archivierung: Termin per `softArchiveById` archiviert, dann Content-Change erneut hochgeladen → neuer Node + aktiv | `deletedAt=null`, 2 Nodes | ✅ |
| AS16 | **BUG-2**: Doppelte aktive `HAS_APPOINTMENT`-Kanten für dieselbe `syncId` verursachen keinen Rollback und werden auf 1 Kante reduziert | Kein `IncorrectResultSizeDataAccessException`, `HAS_APPOINTMENT`-Count = 1 | ✅ |

---

## 2. SyncController — Integrationstests

**Datei:** `SyncControllerIntegrationTest.kt` (neu)  
**Klasse:** `SyncController.kt`

Benötigt `@SpringBootTest` + Testcontainers (Neo4j + Redis) — analog `AppointmentServiceIntegrationTest`.

| # | Test | Erwartung | Status |
|---|---|---|---|
| SC1 | Termin auf Phone, nicht auf Server → `toUpload` enthält syncId | Manifest liefert syncId in `toUpload` | ✅ |
| SC2 | Termin auf Server, nicht auf Phone (+ `confirmedEmpty=false`, Liste nicht leer) → soft-archiviert | `deletedAt!=null` im Neo4j | ✅ |
| SC3 | Phone schickt leere Liste + `confirmedEmpty=false` → G1-Guard: kein Archivieren | Alle Server-Termine aktiv | ✅ |
| SC4 | Phone schickt leere Liste + `confirmedEmpty=true` → Termine werden archiviert | `deletedAt` gesetzt | ✅ |
| SC5 | Duplikat-Termine (gleicher DedupKey, 2 Nodes) → Ältester bleibt, anderer archiviert | 1 aktiver Node, 1 archiviert | ✅ |
| SC6 | SharedCalendar-Termin eines anderen Members → in `toDownload` | syncId in `appointments.toDownload` | ✅ |
| SC7 | Eigener SharedCalendar-Termin → NICHT in `toDownload` | `appointments.toDownload` leer | ✅ |
| SC8 | Kontakt auf Server neuer als auf Phone → in `contacts.toUpdate` | syncId in `contacts.toUpdate` | ✅ |
| SC9 | Slot-Cache nach Manifest-Archivierung invalidiert | Redis-Key weg | ✅ |

---

## 3. BookingController — Controller-Tests + Integrationstests

**Datei:** `BookingControllerTest.kt` ✅ (Basic-Tests vorhanden)  
**Neue Tests:**

### Controller-Tests (`@WebMvcTest`, gemockt)

| # | Test | Erwartung | Status |
|---|---|---|---|
| BC-v1 | `startTime <= 0` → 400 Bad Request | Validierung greift | ✅ |
| BC-v2 | `endTime <= startTime` → 400 Bad Request | Validierung greift | ✅ |
| BC-v3 | `sharedCalendarId` leer → 400 Bad Request | `@NotBlank` greift | ✅ |
| BC-v4 | `title` leer → 400 Bad Request | `@NotBlank` greift | ✅ |
| BC-v5 | Booking nicht gefunden → 404 | `requireBooking()` wirft 404 | ✅ |
| BC-v6 | Cancelled Booking → 404 | `cancelledAt != null` → 404 | ✅ |
| BC-v7 | Booking von anderem Account → 404 (kein 403 — kein Leakage) | Ownership-Check | ✅ |

### Integrationstests (`@SpringBootTest` mit Testcontainers)

| # | Test | Erwartung | Status |
|---|---|---|---|
| BCi1 | Booking erstellen, dann überlappend → 409 Conflict | Overlap-Guard feuert | ✅ |
| BCi2 | Update Booking: eigenes Booking ausgenommen vom Overlap-Check | Update eigenes Booking auf gleiche Zeit → kein 409 | ✅ |
| BCi3 | Booking in SharedCalendar: anderes Member kann Nachricht posten | 201 Created | ✅ |
| BCi4 | Invitee aus anderem Account → gefiltert, Response 207 | `filtered=1`, `saved=0` | ✅ |
| BCi5 | SharedCalendar gelöscht → alle Bookings soft-deleted | Booking returns 404 | ✅ |

---

## 4. ContactsController — Integrationstests

**Datei:** `ContactsControllerTest.kt` ✅ (Basic-Tests vorhanden)  
**Neue Tests:**

| # | Test | Erwartung | Status |
|---|---|---|---|
| CC1 | Upload: neuerer `lastUpdatedAt` überschreibt | `stored=1`, neuer DisplayName aktiv | ✅ |
| CC2 | Upload: älterer `lastUpdatedAt` wird übersprungen | `stored=0`, alter DisplayName bleibt | ✅ |
| CC3 | Upload: Version-Kette korrekt verknüpft | PREVIOUS_VERSION-Kante vorhanden | ✅ (`ContactsHistoryIntegrationTest`) |
| CC4 | **BUG-2**: `findByLookupKey()` darf nicht über Account-Grenzen zurückgeben | Account B findet nicht Account A's Kontakt per lookupKey | ✅ (schlägt fehl bis BUG-2 gefixt) |
| CC5 | Notes mit enthaltenen Zeilenumbrüchen bleiben nach Upload erhalten | Notes-Split liefert Original-Strings | ✅ |
| CC6 | `GET /contacts` gibt nur Kontakte des eigenen Accounts zurück | Andere Accounts nicht sichtbar | ✅ |
| CC7 | `GET /contacts/count` — Account-Isolation auf Count-Endpoint | Andere Account-Zahlen nicht eingerechnet | ✅ |

---

## 5. SharedCalendarController — Controller-Tests + Integrationstests

**Datei:** `SharedCalendarControllerTest.kt` ✅ (Basic-Tests vorhanden)  
**Neue Tests:**

### Controller-Tests (`@WebMvcTest`)

| # | Test | Erwartung | Status |
|---|---|---|---|
| SCS-v1 | Invite generieren ohne Session-Token → 401 | Auth-Check | ✅ |
| SCS-v2 | Invite für Calendar generieren wo Account kein Owner → 403 | Ownership-Check | ✅ |
| SCS-v3 | Owner versucht `leave` → 409 Conflict | Owner kann nicht leaveln | ✅ |
| SCS-v4 | Nicht-Member versucht Calendar zu löschen → 403 | Ownership-Check | ✅ |

### Integrationstests (`@SpringBootTest` mit Testcontainers)

| # | Test | Erwartung | Status |
|---|---|---|---|
| SCSi1 | **BUG-3**: Gleichzeitig zwei Join-Requests mit demselben Code → nur 1 Join erfolgreich | Member-Count = 1 nach Race | ✅ (sequenzieller Beweis) |
| SCSi2 | Invite-Code nach erstem Join verbraucht → zweiter Join liefert 404 | Code nicht mehr in Redis | ✅ |
| SCSi3 | Delete SharedCalendar → alle Bookings soft-deleted + Invite-Codes gelöscht | `softDeleteAllBySharedCalendarId` aufgerufen | ✅ |
| SCSi4 | Leave-Mitglied: Slot-Cache von Owner + verbleibendem Member invalidiert | Redis-Keys weg | ✅ |
| SCSi5 | `GET /shared-calendar/list` gibt nur zugängliche Calendars zurück | Keine fremden Calendars sichtbar | ✅ |

---

## 6. Auth — Integration + Controller

**Datei:** `AuthIntegrationTest.kt` ✅ (vorhanden), `AuthControllerTest.kt` ✅  
**Neue Tests:**

| # | Test | Erwartung | Status |
|---|---|---|---|
| Auth1 | Login mit falschem Passwort → 401 | `passwordEncoder.matches` false | ✅ (`AuthIntegrationTest`) |
| Auth2 | Login mit unbekanntem Username → 401 | Kein Account-Leakage | ✅ (`AuthIntegrationTest`) |
| Auth3 | Doppeltes Register gleicher Username → 409 | `existsByUsername` greift | ✅ (`AuthIntegrationTest`) |
| Auth4 | Logout invalidiert Token → folge-Request mit gleichem Token → 401 | Session aus Redis gelöscht | ✅ (`AuthIntegrationTest`) |
| Auth5 | Token nach 24h abgelaufen → 401 (via Redis TTL) | Redis TTL läuft ab | ✅ |

---

## 7. AppointmentsController — Controller-Tests

**Datei:** `AppointmentsControllerTest.kt` ✅ (vorhanden)  
**Neue Tests:**

| # | Test | Erwartung | Status |
|---|---|---|---|
| AC1 | Upload ohne `X-Sync-Token` → 401 | TokenAuthInterceptor greift | ✅ |
| AC2 | `GET /{syncId}/history` gibt nur Versionen des eigenen Accounts zurück | Client-seitiger Filter korrekt | ✅ |
| AC3 | `GET /appointments/count` gibt korrekte Summe (Personal + SharedCal-Eigene) | Zähler inkl. SharedCal | ✅ |
| AC4 | `GET /appointments/count` zählt doppelte aktive Nodes mit derselben `syncId` nur einmal | Zähler logisch dedupliziert über `syncId` | ✅ |

---

## 8. SlotService — Unit Tests

**Datei:** `SlotServiceTest.kt` ✅ (vorhanden)  
**Neue Tests:**

| # | Test | Erwartung | Status |
|---|---|---|---|
| SlotS1 | Busy-Interval durch Booking blockiert Slot | Kein Slot in Booking-Fenster | ✅ |
| SlotS2 | PADDING_MS (5 min) vor/nach Busy-Interval berücksichtigt | Slot beginnt erst 5 min nach Termin-Ende | ✅ |
| SlotS3 | **BUG-5**: Appointment mit fehlerhaftem Duration-String → skipped + logged, kein Fallback | Fenster bleibt frei | ✅ |
| SlotS4 | Termin an Tagesgrenze (23:45–00:15 Berlin) deckt beide Day-Buckets ab | Kein Slot in beiden Fenstern | ✅ |

---

## 9. HashAppointment — Unit Tests

**Datei:** `HashAppointmentTest.kt` ✅ (vorhanden)  

Hash-Tests sind vollständig. Keine offenen Tests.

---

## 10. TokenAuthInterceptor — Unit Tests

**Datei:** `TokenAuthInterceptorTest.kt` ✅ (vorhanden)  

Interceptor-Tests sind vollständig. Keine offenen Tests.

---

## Priorisierung

| Prio | IDs | Typ | Status |
|---|---|---|---|
| 🔴 Kritisch (Bug) | AS14 (BUG-1: delta archiviert alles) | Integration | ✅ (red) |
| 🔴 Kritisch (Bug) | CC4 (BUG-2: findByLookupKey cross-account) | Integration | ✅ (red) |
| 🟠 Hoch | SC1–SC9 (Manifest-Logik) | Integration | ✅ |
| 🟠 Hoch | BCi1–BCi5 (Booking-Overlap, Member-Access) | Integration | ✅ |
| 🟠 Hoch | SCSi1–SCSi2 (BUG-3: Invite-Reuse) | Integration | ✅ |
| 🟠 Hoch | Auth1–Auth5 | Integration | ✅ |
| 🟡 Mittel | CC1–CC3, CC5–CC7 | Integration | ✅ |
| 🟡 Mittel | AS15 | Integration | ✅ |
| 🟡 Mittel | BC-v1–BC-v7 (Validation) | Controller-Test | ✅ |
| 🟡 Mittel | SCS-v1–SCS-v4 | Controller-Test | ✅ |
| 🔵 Niedrig | SlotS1–SlotS4 | Unit Test | ✅ |
| 🔵 Niedrig | AC1–AC4 | Integration | ✅ |

---

## Offene Besonderheiten

### Was **nicht** isoliert testbar ist (Architektur-bedingt)

| Bereich | Warum |
|---|---|
| Booking-Overlap Race Condition (BUG parallel) | Echter TOCTOU-Race in Neo4j ohne Locking nicht deterministisch testbar — dokumentiert als bekanntes Risiko |
| Auth Register Race Condition | Analog: Neo4j MERGE fehlt auf Account.username — nicht deterministisch ohne echte Parallelität |
| Redis TTL Ablauf | Auth5 benötigt entweder `Thread.sleep(86400s)` oder Mock-TTL — Test ist komplex |

### Test-Setup-Hinweise

- **Testcontainers-Basis:** Analog `AppointmentServiceIntegrationTest` — `Neo4jContainer("neo4j:5").withoutAuthentication()` + `GenericContainer("redis:7-alpine")`
- **`@BeforeEach` Cleanup:** `driver.session().use { it.run("MATCH (n) DETACH DELETE n") }` + Redis flush
- **Auth-Header simulieren:** `request.setAttribute("accountName", ...)` wird in Tests via `EndpointTestSupport.authenticated()` gesetzt
- **MockMvc-Pattern:** `@WebMvcTest` für Controller-Tests ohne Spring-Boot-Context + `@AutoConfigureMockMvc(addFilters = false)`
