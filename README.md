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

## Technologie-Stack

| Komponente | Wert |
|---|---|
| Sprache | Kotlin |
| Framework | Spring Boot 3.5.0 |
| Build | Gradle (Kotlin DSL) |
| Java | 21 |
| Datenbank (primär) | MySQL 8.4 |
| Datenbank (Graph) | Neo4j 5 |
| Cache | Redis 7 |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security (CSRF deaktiviert, alle Requests erlaubt) |

---

## Endpunkte

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/auth/login` | Login mit `{ username, password }` → `{ token, expiresAt }` |
| `POST` | `/auth/logout` | Session beenden: `{ token }` |
| `POST` | `/contacts` | Kontakt-Batch hochladen → `{ revision, contactsStored }` |
| `GET` | `/contacts?accountName=<n>` | Anzahl gespeicherter Kontakte abrufen → `{ accountName, count }` |
| `GET` | `/contacts/all?accountName=<n>` | Alle gespeicherten Kontakte abrufen (für Restore) |
| `POST` | `/appointments` | Termin-Batch hochladen → `{ revision, appointmentsStored }` |
| `GET` | `/appointments/count?accountName=<n>` | Anzahl gespeicherter Termine abrufen → `{ accountName, count }` |
| `GET` | `/appointments?accountName=<n>` | Alle gespeicherten Termine abrufen (für Restore) |
| `POST` | `/booking` | Booking erstellen → `BookingResponse` |
| `GET` | `/booking?accountName=<n>` | Alle Bookings eines Accounts abrufen |
| `GET` | `/booking/:id` | Einzelnes Booking abrufen |
| `PUT` | `/booking/:id` | Booking aktualisieren |
| `DELETE` | `/booking/:id` | Booking löschen |
| `POST` | `/booking/:id/invitees` | Invitees hinzufügen (nur eigene Kontakte) → `{ saved, filtered, invitees }` — `200` oder `207` (**wird bei Neo4j-Migration zu Attendees**) |
| `DELETE` | `/booking/:id/invitees/:contactId` | Invitee entfernen |
| `GET` | `/booking/:id/invitees` | Alle Invitees eines Bookings abrufen |
| `POST` | `/booking/:id/message` | Chat-Nachricht senden |
| `GET` | `/booking/:id/chat` | Alle Nachrichten eines Bookings abrufen |
| `DELETE` | `/booking/:id/message/:messageId` | Nachricht löschen |
| `GET` | `/booking/slots/available?from=&to=&duration=&accountName=` | Freie Zeitfenster berechnen (SlotService + Redis-Cache) |

### Auth
- Sessions werden derzeit in der **MySQL-Datenbank** gespeichert (Tabelle `sessions`) — überleben Server-Neustarts
- **Geplant (Neo4j-Migration):** Sessions werden in **Redis** gespeichert (`@RedisHash`, TTL 24h) — `deleteExpired()` entfällt dann
- Token ist 24h gültig; abgelaufene Sessions werden beim nächsten Login des gleichen Accounts bereinigt
- **Kein Passwort-Check** — alle Credentials werden akzeptiert (Benutzerverwaltung noch nicht implementiert)
- Token wird als HTTP-Header **`X-Sync-Token: <token>`** übergeben (gilt für alle `/contacts`-Endpunkte)

### POST /contacts
- Body: `{ accountName, contacts: [ ContactDto... ] }`
- `ContactDto` enthält jetzt `syncId` (UUID) — generiert von der App, gespeichert in `ContactsContract.Data` (MIME `vnd.de.sync.contacts/uuid`). Aktuell vom Server ignoriert, nach Neo4j-Migration primärer Upsert-Key.
- Upsert-Logik: Kontakt wird nur gespeichert wenn `lastUpdatedAt` neuer ist als der gespeicherte Wert
- Bei existierendem Kontakt: `deleteById` + neu einfügen (wegen JPA `orphanRemoval` bei Kind-Tabellen)
- `@Transactional` — bei Fehler wird alles zurückgerollt

### POST /appointments
- Body: `{ accountName, appointments: [ AppointmentDto... ] }`
- `AppointmentDto` enthält jetzt `syncId` (UUID) — generiert von der App, gespeichert in `CalendarContract.ExtendedProperties` (Key `de.sync.contacts.uuid`). Aktuell vom Server ignoriert, nach Neo4j-Migration primärer Upsert-Key.
- **Kein Skip basierend auf `lastUpdatedAt`** — der Server überschreibt jeden eingehenden Termin immer
- Upsert anhand `deviceId` via `findAllByAccountNameAndDeviceId()` — alle Treffer werden gelöscht, dann neu eingefügt
- `AppointmentDto` enthält: `title`, `dtStart`, `dtEnd`, `allDay`, `rrule`, `timezone`, `location`, `organizer`, `calendarName`, `calendarAccountType`, `calendarAccountName`
- `calendarAccountType = "LOCAL"` → lokaler Gerät-Kalender (wird beim Restore wiederhergestellt)
- `calendarAccountType = "com.google"` etc. → Cloud-Kalender (nur gespeichert, kein Restore nötig)

> **Achtung Spaltentypen:** `location`, `organizer`, `calendar_account_type`, `calendar_account_name` müssen in MySQL `TEXT` sein. Hibernate `ddl-auto=update` ändert keine bestehenden Spalten. Nach initialem Deployment einmalig manuell ausführen:
> ```sql
> ALTER TABLE appointments MODIFY location TEXT, MODIFY organizer TEXT, MODIFY calendar_account_type TEXT, MODIFY calendar_account_name TEXT;
> ```

---

## Datenbankschema

Haupttabelle `contacts` mit FK-Kind-Tabellen:

| Tabelle | Inhalt |
|---|---|
| `contacts` | Hauptdatensatz — `id` (BIGINT AUTO_INCREMENT PK), `lookup_key` (UNIQUE), `account_name`, Namen, Timestamps |
| `contact_phones` | Telefonnummern — FK `contact_id → contacts.id` |
| `contact_emails` | E-Mail-Adressen — FK `contact_id → contacts.id` |
| `contact_addresses` | Postadressen — FK `contact_id → contacts.id` |
| `contact_organizations` | Firmen / Titel — FK `contact_id → contacts.id` |
| `contact_instant_messengers` | Instant-Messenger-Handles — FK `contact_id → contacts.id` |

`lookup_key` ist der stabile Android-Kontakt-Identifier — bleibt UNIQUE, ist aber nicht mehr PK.
PK ist seit Migration (09.04.2026) ein BIGINT AUTO_INCREMENT `id`.

Termin-Tabelle `appointments` (keine Kind-Tabellen):

| Tabelle | Inhalt |
|---|---|
| `appointments` | Alle Terminfelder: `device_id` (PK-Kandidat), `account_name`, `title`, `dt_start`, `dt_end`, `all_day`, `rrule`, `timezone`, `location`, `organizer`, `calendar_name`, `calendar_account_type`, `calendar_account_name`, `last_updated_at` |
| `appointment_attendees` | Termin-Teilnehmer: `appointment_id`, `name`, `email`, `type`, `status`, `contact_lookup_key` |

Booking-Tabellen:

| Tabelle | Inhalt |
|---|---|
| `bookings` | `id` (UUID PK), `account_name`, `title`, `description`, `start_time`, `end_time`, `location_name`, `created_at`, `updated_at` |
| `booking_invitees` | Join-Tabelle: `booking_id` + `contact_id` (BIGINT FK → `contacts.id`, ON DELETE CASCADE) |
| `booking_messages` | Chat-Nachrichten: `id`, `booking_id`, `sender_name`, `text`, `created_at` |

Session-Tabelle:

| Tabelle | Inhalt |
|---|---|
| `sessions` | `token` (PK), `account_name`, `expires_at`, `created_at` |

DDL wird automatisch von Hibernate generiert (`spring.jpa.hibernate.ddl-auto=update`).

---

## Konfiguration

Alle Werte via Umgebungsvariablen (mit Defaults für lokale Entwicklung):

| Variable | Default | Beschreibung |
|---|---|---|
| `MYSQL_HOST` | `localhost` | Datenbankhost |
| `MYSQL_PORT` | `3306` | Datenbankport |
| `MYSQL_DATABASE` | `syncdb` | Datenbankname |
| `MYSQL_USER` | `syncuser` | Datenbankbenutzer |
| `MYSQL_PASSWORD` | `syncsecret` | Datenbankpasswort |
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt-URL |
| `NEO4J_USER` | `neo4j` | Neo4j Benutzer |
| `NEO4J_PASSWORD` | `neo4jsecret` | Neo4j Passwort |
| `REDIS_HOST` | `localhost` | Redis Host |
| `REDIS_PORT` | `6379` | Redis Port |

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
# App-Image
FROM eclipse-temurin:21-jre-alpine
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```
docker/
├── Dockerfile.mysql        # MySQL 8.4 mit Init-Script
└── init/
    └── 00_init.sql         # Schema-Platzhalter (DDL via JPA)
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

# DB-Backup auf der NAS:
cluster-nas.bat backup

# DB-Reset (Volume löschen):
cluster-nas.bat reset-db
```

**Ports auf der NAS:**
- MySQL: `3307` (extern), `3306` (intern im Docker-Netzwerk `homeservice-net`)
- Neo4j: `7474` (Browser UI), `7687` (Bolt)
- Redis: `6379`
- API: `8083`

**NAS Registry:** `192.168.2.223:32768`  
**Images:** `sync-db:latest`, `sync-app-server:latest`
