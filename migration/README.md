# MySQL → Neo4j Datenmigration

## Voraussetzungen

```bash
pip install mysql-connector-python neo4j
```

Python 3.10+ erforderlich (wegen `int | None` Type-Hint).

---

## Schritt 1 — Konnektivität prüfen (Dry-run)

Nur Verbindung testen und Zeilenzählungen ausgeben, **keine Daten schreiben**:

```bash
python migration/migrate_to_neo4j.py \
  --mysql-host <NAS-IP>   --mysql-port 3306 \
  --mysql-user syncuser   --mysql-pass <PASSWORD> --mysql-db syncdb \
  --neo4j-uri bolt://<NAS-IP>:7687 \
  --neo4j-user neo4j      --neo4j-pass <NEO4J_PASSWORD>
```

Das Skript gibt MySQL-Zeilenzählung und aktuelle Neo4j-Node-Anzahl aus, dann beendet es sich.

---

## Schritt 2 — Datenmigration (optional)

> **Wichtig:** Lies diesen Abschnitt komplett, bevor du `--migrate-data` verwendest.

### Das Problem mit syncIds

Die App speichert für jeden Kontakt eine interne `syncId` (UUID) direkt auf dem Gerät in `ContactsContract.Data` bzw. `CalendarContract.ExtendedProperties`. Diese UUIDs sind **auf dem Gerät persistent** und werden beim nächsten Sync an den Server geschickt.

Das Migrations-Skript generiert **neue** UUIDs für alle Kontakte/Termine aus MySQL. Wenn du das Skript ausführst und danach die App synct, entsteht folgendes:

| Server (migriert, UUID-A) | Gerät (UUID-B) | Ergebnis |
|---|---|---|
| N Kontakte mit zufälligen UUIDs | N Kontakte mit eigenen UUIDs | Server hat 2×N nach dem Sync |

Das führt zu **Duplikaten**, wenn Neo4j beim Sync noch die migrierten Daten enthält.

### Empfohlener Ansatz: Wipe + Resync

```bash
python migration/migrate_to_neo4j.py \
  --mysql-host <NAS-IP>   --mysql-port 3306 \
  --mysql-user syncuser   --mysql-pass <PASSWORD> --mysql-db syncdb \
  --neo4j-uri bolt://<NAS-IP>:7687 \
  --neo4j-user neo4j      --neo4j-pass <NEO4J_PASSWORD> \
  --migrate-data --wipe-neo4j
```

`--wipe-neo4j` löscht alle `Contact`- und `Appointment`-Nodes in Neo4j **vor** der Migration. Nach der Migration:

1. Konto neu registrieren in der App (neues Passwort setzen — das alte System hatte keine Passwörter).
2. App synct automatisch → alle Kontakte/Termine werden vom Gerät hochgeladen.
3. Da Neo4j die migrierten UUIDs enthält (von `--migrate-data`) **und** die Gerät-UUIDs nach dem Sync: Duplikate entstehen.

### Sauberster Ansatz: Kein --migrate-data

Für Einzelgerät-Setups (eine Person, ein Telefon):

```bash
# Nur Dry-run, KEIN --migrate-data
python migration/migrate_to_neo4j.py ...

# Dann: Neo4j leer lassen.
# Statt Migration: Daten kommen automatisch vom Gerät beim nächsten Sync.
```

**Warum das besser ist:**
- Das Gerät ist die einzige Quelle der Wahrheit (alle Kontakte/Termine sind auf dem Gerät).
- Der Server ist nur ein Backup.
- Beim ersten Sync nach dem Deployment schickt die App alle Kontakte und Termine hoch → Neo4j wird korrekt befüllt.
- Keine UUID-Konflikte, keine Duplikate.

---

## Schritt 3 — JPA aus dem Server entfernen

Sobald Schritt 1 (und optional Schritt 2) erfolgreich waren:

1. Neues Server-Deployment deployen (Phase 5 des Migrationplans).
2. In der App: Account entfernen und neu anlegen (Settings → Accounts → Sync App → Account löschen → Add Account → **"Neues Konto anlegen" anhaken**).
3. Erster Sync: alle Daten werden vom Gerät hochgeladen.

---

## Schritt 4 — MySQL stoppen (optional)

Wenn der Server erfolgreich ohne MySQL läuft und alle Daten in Neo4j sind:

```bash
# Im docker-compose auf der NAS:
docker compose stop mysql
# oder ganz entfernen:
docker compose rm mysql
```

---

## Skript-Parameter

| Parameter | Standard | Beschreibung |
|---|---|---|
| `--mysql-host` | localhost | MySQL-Host |
| `--mysql-port` | 3306 | MySQL-Port |
| `--mysql-user` | — | MySQL-Benutzername (Pflicht) |
| `--mysql-pass` | — | MySQL-Passwort (Pflicht) |
| `--mysql-db` | — | Datenbankname (Pflicht) |
| `--neo4j-uri` | bolt://localhost:7687 | Neo4j Bolt URI |
| `--neo4j-user` | neo4j | Neo4j-Benutzername |
| `--neo4j-pass` | — | Neo4j-Passwort (Pflicht) |
| `--migrate-data` | false | Daten tatsächlich migrieren |
| `--wipe-neo4j` | false | Alle Contact/Appointment-Nodes in Neo4j löschen vor Migration |
