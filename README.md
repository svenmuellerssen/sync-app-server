# sync-app-server

Spring Boot Backend für die Sync App. Nimmt Kontakt-Backups vom Android-Gerät entgegen und speichert sie in MySQL.

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
