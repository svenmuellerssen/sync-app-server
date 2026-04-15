# sync-app-server

Spring Boot Backend für die Sync App. Nimmt Kontakt- und Termin-Backups vom Android-Gerät entgegen, ermöglicht bidirektionale Synchronisierung und speichert alle Daten in Neo4j.

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

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/auth/register` | Neuen Account registrieren: `{ username, password }` → `{ token, expiresAt }` |
| `POST` | `/auth/login` | Login: `{ username, password }` → `{ token, expiresAt }` |
| `POST` | `/auth/logout` | Session beenden: `{ token }` |
| `POST` | `/sync/manifest` | Bidirektionaler Sync-Vergleich → `{ contacts: { toUpload, toDownload, toUpdate }, appointments: { … } }` |
| `POST` | `/contacts` | Kontakt-Batch hochladen → `{ revision, contactsStored }` |
| `GET` | `/contacts?accountName=<n>` | Anzahl gespeicherter Kontakte → `{ accountName, count }` |
| `GET` | `/contacts/all?accountName=<n>` | Alle Kontakte abrufen (für Restore) |
| `POST` | `/appointments` | Termin-Batch hochladen → `{ revision, appointmentsStored }` |
| `GET` | `/appointments/count?accountName=<n>` | Anzahl gespeicherter Termine → `{ accountName, count }` |
| `GET` | `/appointments?accountName=<n>` | Alle Termine abrufen (für Restore) |
| `POST` | `/shared-calendar` | Neuen Shared Calendar anlegen: `{ name, color? }` → `SharedCalendarDto` |
| `GET` | `/shared-calendar/list?accountName=<n>` | Alle Shared Calendars des Accounts → `List<SharedCalendarDto>` |
| `GET` | `/shared-calendar/invite/{calendarId}` | Einladungscode generieren (7 Zeichen, TTL 10 Min) → `{ code, expiresInSeconds, calendarId }` |
| `POST` | `/shared-calendar/join` | Kalender beitreten: `{ inviteCode }` → `SharedCalendarDto` |
| `DELETE` | `/shared-calendar/{calendarId}/leave` | Kalender verlassen → 204 |
| `GET` | `/calendar/google?accountName=<n>` | Google-Kalender-Info mit bekannten Mitgliedern → `List<GoogleCalendarDto>` |

### Auth
- Accounts werden in Neo4j als `(:Account {username, passwordHash, createdAt})` gespeichert
- Passwörter werden mit **BCrypt** gehasht — `/auth/register` legt einen neuen Account an, `/auth/login` prüft das Passwort
- Sessions werden in **Redis** gespeichert (`@RedisHash("session")`, TTL 24h) — überleben Server-Neustarts, laufen automatisch ab
- Token wird als HTTP-Header **`X-Sync-Token: <token>`** übergeben

### POST /sync/manifest
- Body: `{ accountName, contacts: [{ syncId, lastUpdatedAt }], appointments: [{ syncId, lastUpdatedAt }] }`
- Server vergleicht die mitgeschickten SyncIds+Timestamps mit dem eigenen Bestand
- Antwort: welche SyncIds beim Client fehlen (`toDownload`), beim Server fehlen (`toUpload`) oder veraltet sind (`toUpdate`)
- Die App respektiert einen **Dry-run-Modus**: ist dieser aktiv, schickt die App das Manifest, schreibt aber nichts auf das Gerät oder den Server

### POST /contacts
- Body: `{ accountName, contacts: [ ContactDto… ] }`
- `ContactDto` enthält `syncId` (UUID) — generiert von der App, gespeichert in `ContactsContract.Data` (MIME `vnd.de.sync.contacts/uuid`). Primärer Upsert-Key in Neo4j.
- Upsert-Logik: existiert der Kontakt per `syncId` bereits → löschen + neu einfügen. Andernfalls neu anlegen.

### POST /appointments
- Body: `{ accountName, appointments: [ AppointmentDto… ] }`
- `AppointmentDto` enthält `syncId` (UUID) — gespeichert in `CalendarContract.ExtendedProperties` (Key `de.sync.contacts.uuid`)
- `calendarAccountType = "LOCAL"` → lokaler Gerät-Kalender (wird beim Restore wiederhergestellt, inkl. `calendarColor`)
- `calendarAccountType = "com.google"` → Google-Kalender (nur gespeichert + in `GoogleCalendarNode` verknüpft, kein Restore)
- `calendarAccountType = "de.sync.contacts"` → Server Shared Calendar (bidirektionaler Sync, Restore auf allen Mitglieder-Geräten)
- `accessLevel: Int?` — Android `ACCESS_LEVEL` des Kalenders (100=READ, 500=CONTRIBUTOR, 600=EDITOR, 700=OWNER). Bestimmt ob Booking erlaubt ist.

---

## Kalender-Konzepte

### Shared Calendars (Server-verwaltet)
Eigene Kalender die auf dem Server angelegt und per Invite-Code geteilt werden:
- Beliebig viele pro Account
- Mitglieder treten per 7-stelligem Code bei (Redis, TTL 10 Min, einmalig nutzbar)
- Events erscheinen auf Mitglieder-Geräten als lokaler Kalender unter `FuTecSystemsInc` Account
- `calendarAccountType = "de.sync.contacts"` — kein Überlauf in Google Calendar möglich
- Neo4j: `(:SharedCalendar)-[:HAS_MEMBER]->(:Account)`, Events: `(:Appointment)-[:BELONGS_TO_SHARED_CAL]->(:SharedCalendar)`

### Google Shared Calendar Tracking
Bestehende geteilte Google-Kalender werden getracked:
- Events werden immer hochgeladen (für Slot-Blocking und Backup)
- Kein Download (Google übernimmt Sync zwischen Geräten)
- Bekannte Kontakte die am Kalender beteiligt sind werden als `[:HAS_MEMBER]->(:Contact)` gespeichert
- `accessLevel >= 500` → Booking auf diesem Kalender erlaubt
- Neo4j: `(:GoogleCalendar {calendarId, displayName, calendarAccountName, accessLevel})-[:HAS_MEMBER]->(:Contact)`

---

## Datenmodell (Neo4j)

```
(:Account {username, passwordHash, createdAt})

(:Contact {syncId, lookupKey, accountName, displayName, givenName, familyName,
           middleName, namePrefix, nameSuffix, phoneticGivenName,
           phoneticMiddleName, phoneticFamilyName, notes,
           lastUpdatedAt, createdAt})
  -[:HAS_PHONE]->        (:Phone         {number, type, label})
  -[:HAS_EMAIL]->        (:Email         {address, type, label})
  -[:HAS_ADDRESS]->      (:Address       {street, city, region, postCode, country, type, label})
  -[:HAS_ORGANIZATION]-> (:Organization  {company, title, department})
  -[:HAS_IM]->           (:InstantMessenger {handle, protocol, customProtocol})

(:Appointment {syncId, accountName, title, description, dtStart, dtEnd,
               duration, allDay, timezone, rrule, location, organizer,
               calendarName, calendarAccountType, calendarAccountName,
               calendarColor, accessLevel, lastUpdatedAt, createdAt})
  -[:HAS_ATTENDEE]->(  :Attendee {name, email, type, status})
  -[:BELONGS_TO_SHARED_CAL]-> (:SharedCalendar)   ← nur wenn calendarAccountType="de.sync.contacts"
  -[:BELONGS_TO_GOOGLE_CAL]-> (:GoogleCalendar)   ← nur wenn calendarAccountType="com.google"

(:SharedCalendar {calendarId, name, color, createdAt, createdBy})
  -[:HAS_MEMBER]-> (:Account)     ← alle Mitglieder

(:GoogleCalendar {calendarId, displayName, calendarAccountName, color, accessLevel, accountName})
  -[:HAS_MEMBER]-> (:Contact)     ← bekannte Kontakte die beteiligt sind

(:Session {token, accountName, ttlSeconds, createdAt})              ← Redis (@RedisHash, TTL 24h)
(:SharedCalendarInvite {inviteCode, calendarId, createdBy, ttlSeconds})  ← Redis (TTL 10 Min, einmalig)
```

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

## Datenmigration (MySQL → Neo4j)

Falls ältere Daten aus MySQL übernommen werden sollen, siehe `migration/README.md`.

**Empfehlung für Einzelgerät-Setup:** Kein Migrations-Skript nötig. Einfach Account neu registrieren und App synken lassen — die App lädt alle Kontakte und Termine automatisch hoch.

---
