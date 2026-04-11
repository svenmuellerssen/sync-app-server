# Code-Analyse: sync-app-server

Stand: 11.04.2026 — Analyse aller Kotlin-Quelldateien auf logische Lücken, Best Practices und Performance.

---

## Schweregrade

| Symbol | Bedeutung |
|---|---|
| 🔴 | Kritisch — muss behoben werden |
| 🟠 | Hoch — sollte behoben werden |
| 🟡 | Mittel — Refactoring empfohlen |
| 🔵 | Niedrig — Code-Qualität |
| ✅ | By Design — bekannt und akzeptiert |

---

## 1. Authentifizierung & Sicherheit

### 🔴 Token wird in Controllern nie validiert

**Betroffen:** `ContactsController`, `AppointmentsController`

`@RequestHeader("X-Sync-Token") token: String` wird zwar empfangen, aber nie gegen die `sessions`-Tabelle geprüft. Jeder beliebige String als Token reicht aus.

**Fix:** Zentrale `requireValidToken(token)` Hilfsfunktion oder `HandlerInterceptor`:
```kotlin
fun requireValidToken(token: String, sessionRepository: SessionRepository): SessionEntity {
    val session = sessionRepository.findById(token).orElse(null)
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
    if (System.currentTimeMillis() >= session.expiresAt)
        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token expired")
    return session
}
```

---

### 🔴 accountName-Parameter wird nicht gegen Token geprüft (Privilege Escalation)

**Betroffen:** `ContactsController.getContacts()`, `ContactsController.getContactCount()`, `AppointmentsController.getAppointments()`

`accountName` kommt als Query-Parameter vom Client. Ein Angreifer mit gültigem Token kann durch Ändern des Parameters auf fremde Daten zugreifen.

**Fix:** `accountName` aus dem Token (Session) lesen, nicht aus dem Request:
```kotlin
val session = requireValidToken(token, sessionRepository)
val contacts = contactRepository.findAllByAccountName(session.accountName)
```

---

### 🔴 `isTokenValid()` in AuthController existiert, wird aber nie genutzt

**Betroffen:** `AuthController`

Die Methode ist `public` und korrekt implementiert, wird aber nirgendwo aufgerufen.

**Fix:** In `requireValidToken()` integrieren oder löschen.

---

### ✅ Kein Passwort-Check beim Login

By design: `POST /auth/login` akzeptiert jeden non-leeren Username/Password. Keine Benutzerverwaltung implementiert. Akzeptiert für Single-Tenant-Privatserver.

---

### 🟠 `@Modifying` ohne `@Transactional` in SessionRepository

**Betroffen:** `SessionRepository.deleteExpired()`

`@Modifying` ohne `@Transactional` kann bei manchen JPA-Providern zu Fehlern führen, da keine Transaktion aktiv ist.

**Fix:**
```kotlin
@Modifying
@Transactional
@Query("DELETE FROM SessionEntity s WHERE s.expiresAt < :nowMs")
fun deleteExpired(nowMs: Long): Int
```

---

### 🟡 SessionRepository — kein Index auf `expiresAt`

`deleteExpired()` filtert nach `expiresAt < :nowMs`, aber es gibt keinen DB-Index. Bei vielen Sessions = Full Table Scan.

**Fix in SessionEntity:**
```kotlin
@Table(name = "sessions", indexes = [Index(name = "idx_sessions_expires_at", columnList = "expires_at")])
```

---

## 2. Performance — N+1 Query Problem

### 🔴 EAGER Loading auf allen OneToMany-Collections

**Betroffen:** `ContactEntity` (5×), `AppointmentEntity` (1×)

Alle OneToMany-Relations verwenden `FetchType.EAGER`. Das bedeutet: beim Laden von N Kontakten werden N×6 SQL-Queries ausgeführt (1 für Kontakte + 5 für jede Collection).

Bei 145 Kontakten: **870 SELECT-Statements** für `GET /contacts/all`.

**Fix:** `FetchType.LAZY` + `@EntityGraph` oder `JOIN FETCH` im Repository:
```kotlin
@OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
@JoinColumn(name = "contact_id")
val phoneNumbers: MutableList<ContactPhoneEntity> = mutableListOf()
```
```kotlin
// In ContactRepository:
@Query("SELECT c FROM ContactEntity c LEFT JOIN FETCH c.phoneNumbers LEFT JOIN FETCH c.emailAddresses WHERE c.accountName = :accountName")
fun findAllByAccountNameWithDetails(accountName: String): List<ContactEntity>
```

> **Hinweis:** `spring.jpa.open-in-view=false` ist bereits gesetzt — LAZY loading erfordert, dass alle Zugriffe innerhalb der Transaktion geschehen. Der `toDto()`-Aufruf muss also innerhalb `@Transactional` bleiben.

---

### 🟠 Keine Pagination auf GET-Endpunkten

**Betroffen:** `GET /contacts/all`, `GET /appointments`

Beide laden alle Datensätze eines Accounts in den Speicher. Bei wachsendem Datenbestand kann das zu OutOfMemoryErrors führen.

**Empfehlung:** `Pageable` + `Page<T>` für alle List-Endpunkte einführen.

---

## 3. Fehlende Datenbank-Constraints und Indizes

### 🟠 Kein zusammengesetzter Index auf häufig gefilterten Spalten

**Betroffen:** `contacts.account_name`, `appointments.account_name + device_id`

Alle wichtigen Queries filtern nach `account_name`, aber kein Index ist definiert.

**Fix in ContactEntity:**
```kotlin
@Table(
    name = "contacts",
    indexes = [Index(name = "idx_contacts_account_name", columnList = "account_name")],
    uniqueConstraints = [UniqueConstraint(name = "uq_contacts_lookup_key", columnNames = ["lookup_key"])]
)
```

**Fix in AppointmentEntity:**
```kotlin
@Table(
    name = "appointments",
    indexes = [
        Index(name = "idx_appointments_account_name", columnList = "account_name"),
        Index(name = "idx_appointments_account_device", columnList = "account_name,device_id")
    ]
)
```

---

### 🟠 lookup_key UNIQUE ohne accountName-Scope

**Betroffen:** `ContactEntity`

`lookup_key` ist global UNIQUE. Zwei verschiedene Android-Accounts könnten theoretisch denselben `lookup_key` generieren (unwahrscheinlich, aber möglich). Korrekt wäre ein `UNIQUE (account_name, lookup_key)`.

**Fix:**
```kotlin
uniqueConstraints = [UniqueConstraint(name = "uq_contacts_account_lookup", columnNames = ["account_name", "lookup_key"])]
```
> Benötigt außerdem Anpassung in `findByLookupKey()` → `findByAccountNameAndLookupKey()` im Controller.

---

### 🟠 Kein UniqueConstraint auf (accountName, deviceId) in appointments

Der Controller behandelt `accountName + deviceId` als eindeutigen Identifier (löscht alle vorhandenen, speichert neuen), aber die DB erzwingt das nicht. Duplikate können durch parallele Uploads entstehen.

**Fix:**
```kotlin
uniqueConstraints = [UniqueConstraint(name = "uq_appointments_account_device", columnNames = ["account_name", "device_id"])]
```

---

## 4. Datenmodell-Probleme

### 🟡 AttendeeType und AttendeeStatus als rohe `Int` statt Enum

**Betroffen:** `AppointmentAttendeeEntity`, `AttendeeDto`

`type` und `status` speichern Android-Konstanten (0–3 bzw. 0–4) als `Int?`. Kein Typsicherheit, schlechte Lesbarkeit.

**Empfehlung:**
```kotlin
// Dokumentation der Werte (Android CalendarContract.Attendees):
// type:   0=NONE, 1=REQUIRED, 2=OPTIONAL, 3=RESOURCE
// status: 0=NONE, 1=ACCEPTED, 2=DECLINED, 3=INVITED, 4=TENTATIVE
```
Vorerst als Kommentar ausreichend. Enum-Migration ist ein Breaking Change für bestehende Daten.

---

### 🟡 `ContactDtoResponse` verwendet Request-DTOs als Response-Typen

**Betroffen:** `ContactsController`

`ContactDtoResponse` enthält `List<PhoneDtoRequest>` etc. Request-Typen sollten nie als Response verwendet werden — das koppelt Ein- und Ausgabe.

**Empfehlung:** Separate `PhoneDtoResponse`, `EmailDtoResponse` etc. anlegen, auch wenn sie vorerst identisch sind.

---

### 🔵 `revision` in BackupResponse ist zufällige UUID ohne Bedeutung

**Betroffen:** `ContactsController`, `AppointmentsController`

`revision` wird als `UUID.randomUUID()` generiert, aber nirgendwo gespeichert oder genutzt. Die App zeigt ihn im Dashboard an, aber er hat keine semantische Bedeutung (kein Vergleich mit vorherigem Revision möglich).

**Empfehlung:** Entweder echte Revisionslogik implementieren (z.B. `MAX(lastUpdatedAt)` zurückgeben) oder Feld aus Response entfernen.

---

## 5. Fehlerbehandlung

### 🟠 Kein globaler Exception Handler

Wenn ein unerwarteter Fehler auftritt (DB down, NPE, etc.), gibt Spring Boot den vollen Stack-Trace im Response zurück. Das leakt interne Details.

**Fix:** `@RestControllerAdvice` hinzufügen:
```kotlin
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(Exception::class)
    fun handleGeneral(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("Unhandled exception", e)
        return ResponseEntity.status(500).body(mapOf("error" to "Internal server error"))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<Map<String, String>> {
        return ResponseEntity.status(e.statusCode).body(mapOf("error" to (e.reason ?: e.message ?: "Error")))
    }
}
```

---

### 🟡 Kein Logging in Controllern

Keine strukturierten Logs für Sync-Operationen. Bei Fehlern gibt es keinen Audit-Trail.

**Empfehlung:**
```kotlin
private val log = LoggerFactory.getLogger(ContactsController::class.java)
// ...
log.info("Contact sync: account={} uploaded={} skipped={}", batch.accountName, stored, batch.contacts.size - stored)
```

---

## 6. application.properties

### 🟡 SSL deaktiviert in MySQL-Verbindung

`useSSL=false` in der Datasource-URL. Im LAN-Betrieb akzeptabel, aber explizit dokumentieren.

### 🔵 Kein Connection-Pool konfiguriert

HikariCP (Standard in Spring Boot) läuft mit Default-Einstellungen (max 10 Connections). Bei parallelen Sync-Vorgängen könnten Connections erschöpft werden.

**Empfehlung für `application.properties`:**
```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

### 🔵 Kein Server-Port explizit gesetzt

Default ist 8080 intern (Docker mapped auf 8083 extern). Explizit setzen:
```properties
server.port=8080
```

---

## 7. Zusammenfassung — Priorisierte To-Do-Liste

### Sofort (vor BookingController-Implementierung)
- [ ] Token-Validierung in allen Controllern (`requireValidToken()`)
- [ ] `accountName` aus Session lesen, nicht aus Request-Parameter
- [ ] `@Transactional` zu `SessionRepository.deleteExpired()` hinzufügen

### Kurzfristig (Performance)
- [ ] `FetchType.LAZY` für alle OneToMany-Collections
- [ ] Index auf `contacts.account_name` + `appointments.account_name/device_id`
- [ ] `UniqueConstraint` auf `(account_name, lookup_key)` in contacts
- [ ] `UniqueConstraint` auf `(account_name, device_id)` in appointments

### Mittelfristig
- [ ] `@RestControllerAdvice` Global Exception Handler
- [ ] Strukturiertes Logging in Controllern
- [ ] Separate Response-DTOs von Request-DTOs trennen
- [ ] Pagination für List-Endpunkte

### Niedrig
- [ ] HikariCP Connection Pool konfigurieren
- [ ] `server.port=8080` explizit setzen
- [ ] `revision` in BackupResponse mit echtem Wert befüllen oder entfernen
