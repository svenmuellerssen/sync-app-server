# Bekannte Bugs: sync-app-server

Stand: 2026-04-30

---

## Übersicht

| ID | Schwere | Titel | Status |
|---|---|---|---|
| BUG-1 | 🔴 KRITISCH | Delta-Upload archiviert alle vorhandenen Appointments | offen |
| BUG-2 | 🔴 HOCH | `findByLookupKey()` ohne Account-Filter — Cross-Account-Leakage | offen |
| BUG-3 | 🟠 MITTEL | Invite-Code kann mehrfach genutzt werden (nicht-atomares Join) | offen |
| BUG-4 | 🟠 MITTEL | HAS_APPOINTMENT-Kante vor `save()` entfernt — Orphan-Risiko | offen |
| BUG-5 | 🟡 NIEDRIG | Fehlerhafte Duration-Strings fallen auf 15 min zurück | ✅ behoben |
| BUG-6 | 🟡 NIEDRIG | DedupKey für Recurring-Appointments kann falsch deduplizieren | offen |
| BUG-7 | 🟠 MITTEL | Register Race Condition: kein Unique-Constraint auf `Account.username` | offen |

---

## BUG-1 🔴 — Delta-Upload archiviert alle Appointments

**Fundort:** `AppointmentService.processBatch()` ~L70  
**Reproduzierbar:** Ja (bei jedem Manifest-basierten Sync nach erstem Sync)

### Beschreibung

`processBatch()` ruft `archiveOrphanedPersonalAppointments(accountName, knownSyncIds, now)` auf,
wobei `knownSyncIds` nur die syncIds der **aktuell hochgeladenen** DTOs enthält.

Bei einem Manifest-basierten Delta-Sync schickt die App nur neue/geänderte Termine (`toUpload`),
nicht die gesamte Phone-Liste. Dadurch werden **alle** anderen Server-Termine als "orphaned" betrachtet
und soft-archiviert — obwohl sie noch auf dem Gerät vorhanden sind.

Der `SyncController` behandelt das Archivieren in `buildAppointmentManifest()` korrekt (nur Termine
die wirklich nicht mehr auf dem Phone sind). Die Archivierung in `processBatch` ist daher redundant
und destruktiv für inkrementelle Syncs.

### Fix

`archiveOrphanedPersonalAppointments` aus `processBatch()` entfernen. Die Archivierung erfolgt
vollständig über den `SyncController`.

**Achtung:** `AS11` testet das alte Verhalten (Voll-Batch) und läuft durch — aber validiert inadvertent
das Buggy-Verhalten. Nach Fix muss AS11 prüfen, dass Archivierung über SyncController korrekt läuft.

**Test:** AS14 in `todo-tests.md`

---

## BUG-2 🔴 — `findByLookupKey()` ohne Account-Filter

**Fundort:** `ContactRepository.kt` L10, verwendet in `AppointmentService.processSingle()` ~L122  
**Reproduzierbar:** Ja (wenn zwei Accounts einen Kontakt mit gleichem `lookupKey` haben)

### Beschreibung

```kotlin
fun findByLookupKey(lookupKey: String): ContactNode?
```

Diese Query hat keinen `accountName`-Filter. Da `lookupKey` ein Android-interner Identifier ist,
können auf verschiedenen Geräten Kontakte mit identischem `lookupKey` existieren.

Ergebnis: `AppointmentService.processSingle()` kann bei Attendee-Auflösung einen Kontakt aus einem
**anderen Account** zurückgeben. Das ist ein Data-Privacy-Problem.

Andere Repository-Methoden (z.B. `findAllByAccountNameAndLookupKeyIn()`) sind korrekt gefiltert.

### Fix

```kotlin
fun findByLookupKeyAndAccountName(lookupKey: String, accountName: String): ContactNode?
```

Alle Aufrufe in `AppointmentService` anpassen.

**Test:** CC4 in `todo-tests.md`

---

## BUG-3 🟠 — Invite-Code kann mehrfach genutzt werden

**Fundort:** `SharedCalendarController.joinSharedCalendar()` L112–128  
**Reproduzierbar:** Nur bei Race Condition (zwei parallele Requests)

### Beschreibung

```
1. Request A: Redis.get(code) → InviteEntity gefunden
2. Request B: Redis.get(code) → InviteEntity gefunden (noch nicht gelöscht!)
3. Request A: member hinzufügen, Redis.delete(code)
4. Request B: member hinzufügen, Redis.delete(code) → NoOp
```

Beide Requests jointen erfolgreich, obwohl ein Invite-Code nur einmal gültig sein sollte.

### Fix

Atomares Redis-Pattern: `GETDEL` (Redis 6.2+) statt separates `GET` + `DELETE`.
Alternativ: Lua-Script für atomares Check-and-Delete.

**Test:** SCSi1 in `todo-tests.md`

---

## BUG-4 🟠 — HAS_APPOINTMENT-Kante vor `save()` entfernt

**Fundort:** `AppointmentService.processSingle()` ~L131 vs ~L167  
**Reproduzierbar:** Nur bei Datenbankfehler während `save()`

### Beschreibung

```kotlin
removeHasAppointmentEdge(calendarNode, existing)  // L131 — Kante weg
// ...
val saved = appointmentRepository.save(newNode)    // L167 — falls hier Exception
setHasAppointmentEdge(calendarNode, saved)         // wird nie erreicht
```

Wenn `save()` fehlschlägt (DB-Timeout, Neo4j-Fehler), gibt es keinen aktiven Appointment-Node mehr.
Der alte Node existiert noch in Neo4j, ist aber über keine `HAS_APPOINTMENT`-Kante erreichbar.

### Fix

Reihenfolge ändern: erst `save()`, dann erst alte Kante entfernen und neue setzen.
Oder: `@Transactional` sicherstellen, dass der gesamte Swap atomar rollt.

---

## BUG-5 🟡 ✅ — Fehlerhafte Duration-Strings fallen auf 15 min zurück

**Status: BEHOBEN**

**Fix:**
- **Server** (`SlotService.kt`): `runCatching` aus `endTime()` entfernt. `Duration.parse()` wirft jetzt `DateTimeParseException` bei ungültigem Format. Aufrufstelle in `getOrBuildDayBucket()` fängt `DateTimeParseException` per Appointment, loggt `syncId` + Duration-String, und überspringt den Eintrag (kein 15-min-Fallback mehr).
- **App** (`CalendarReader.kt`): neue `normalizeEventDuration()` Funktion — konvertiert RFC 2445 `P{n}W` → `P{7n}D` (ISO kompatibel), lässt alle anderen ISO-Formate unverändert, gibt `null` + Log-Warnung für unbekannte Formate zurück.

**Tests:** SlotS3 (`SlotServiceTest.kt`), `CalendarReaderTest.kt` (Android unit tests)

---

## BUG-6 🟡 — DedupKey für Recurring Appointments

**Fundort:** `SyncController.buildAppointmentManifest()` ~L92–99  
**Reproduzierbar:** Bei Recurring Appointments mit Exception-Instanzen

### Beschreibung

```kotlin
data class DedupKey(val title: String, val dtStart: Long, val dtEnd: Long, val rrule: String?)
```

Zwei modifizierte Instanzen einer Recurring-Series haben `dtStart != dtEnd` des anderen, also kein
DedupKey-Konflikt. **Aber:** Eine Exception-Instanz (gleiche Zeit, geänderter Inhalt) hat denselben
`DedupKey` wie die ursprüngliche Instanz → falsches Dedup.

Praktische Auswirkung: gering, da Exception-Instanzen unterschiedliche `syncId` haben und der
primäre Upsert über `syncId` läuft. DedupKey ist nur für Duplikat-Detection bei Mehrfach-Uploads
relevant.

---

## BUG-7 🟠 — Register Race Condition

**Fundort:** `AuthController.register()`  
**Reproduzierbar:** Nur bei gleichzeitigen parallelen Register-Requests

### Beschreibung

```kotlin
if (accountRepository.existsByUsername(username)) throw ...  // Check
accountRepository.save(AccountNode(...))                      // Save — nicht atomar
```

Zwei gleichzeitige Registrierungen mit demselben Username können beide den `existsByUsername`-Check
passieren, bevor einer von ihnen gespeichert hat. Ergebnis: zwei `AccountNode`-Nodes mit gleichem
`username` in Neo4j.

Ohne `UNIQUE` Constraint im Neo4j-Schema ist das nicht durch die Datenbank verhindert.

### Fix

Neo4j Unique Constraint auf `Account.username`:
```cypher
CREATE CONSTRAINT account_username_unique FOR (a:Account) REQUIRE a.username IS UNIQUE
```

Im Spring-Code: Fehlerbehandlung für `ConstraintViolationException` → 409.

---

## Nicht deterministisch testbar

| Bug | Grund |
|---|---|
| BUG-3 Race Condition | Echter TOCTOU braucht echte Parallelität; `getdel`-Fix ist einfacher |
| BUG-7 Race Condition | Neo4j Unique Constraint als Fix; Race selbst nicht reproduzierbar in JUnit |
