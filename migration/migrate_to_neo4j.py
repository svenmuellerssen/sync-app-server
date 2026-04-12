#!/usr/bin/env python3
"""
MySQL → Neo4j Migration Script for sync-app-server.

IMPORTANT: Read migration/README.md before running this script.

Usage:
    python migrate_to_neo4j.py \
        --mysql-host 127.0.0.1 --mysql-port 3306 \
        --mysql-user syncuser --mysql-pass syncsecret --mysql-db syncdb \
        --neo4j-uri bolt://localhost:7687 \
        --neo4j-user neo4j --neo4j-pass neo4jsecret \
        [--migrate-data]

By default (without --migrate-data) the script only prints row counts from MySQL
and exits. This lets you verify connectivity before committing to a full migration.

With --migrate-data the script migrates contacts and appointments to Neo4j.
Read the README for the duplicate-sync warning before using this flag.
"""

import argparse
import sys
import uuid
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# Dependency check
# ---------------------------------------------------------------------------
try:
    import mysql.connector
except ImportError:
    print("ERROR: mysql-connector-python not installed.")
    print("  pip install mysql-connector-python")
    sys.exit(1)

try:
    from neo4j import GraphDatabase
except ImportError:
    print("ERROR: neo4j driver not installed.")
    print("  pip install neo4j")
    sys.exit(1)


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(description="Migrate sync-app data from MySQL to Neo4j")
    p.add_argument("--mysql-host", default="localhost")
    p.add_argument("--mysql-port", type=int, default=3306)
    p.add_argument("--mysql-user", required=True)
    p.add_argument("--mysql-pass", required=True)
    p.add_argument("--mysql-db", required=True)
    p.add_argument("--neo4j-uri", default="bolt://localhost:7687")
    p.add_argument("--neo4j-user", default="neo4j")
    p.add_argument("--neo4j-pass", required=True)
    p.add_argument(
        "--migrate-data",
        action="store_true",
        default=False,
        help="Actually migrate contact/appointment data. Without this flag only counts are printed.",
    )
    p.add_argument(
        "--wipe-neo4j",
        action="store_true",
        default=False,
        help="Delete all Contact and Appointment nodes in Neo4j before migrating. Use with care.",
    )
    return p.parse_args()


# ---------------------------------------------------------------------------
# MySQL helpers
# ---------------------------------------------------------------------------
def mysql_connect(args):
    conn = mysql.connector.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_pass,
        database=args.mysql_db,
    )
    print(f"[MySQL] Connected to {args.mysql_host}:{args.mysql_port}/{args.mysql_db}")
    return conn


def fetch_all(cursor, query, params=None):
    cursor.execute(query, params or ())
    cols = [d[0] for d in cursor.description]
    return [dict(zip(cols, row)) for row in cursor.fetchall()]


def print_mysql_counts(conn):
    cur = conn.cursor()
    tables = [
        "contacts",
        "contact_phones",
        "contact_emails",
        "contact_addresses",
        "contact_organizations",
        "contact_instant_messengers",
        "appointments",
        "appointment_attendees",
        "sessions",
    ]
    print("\n[MySQL] Row counts:")
    for t in tables:
        cur.execute(f"SELECT COUNT(*) FROM `{t}`")
        count = cur.fetchone()[0]
        print(f"  {t:40s} {count:6d}")
    cur.close()


# ---------------------------------------------------------------------------
# Neo4j helpers
# ---------------------------------------------------------------------------
def neo4j_connect(args):
    driver = GraphDatabase.driver(
        args.neo4j_uri,
        auth=(args.neo4j_user, args.neo4j_pass),
    )
    driver.verify_connectivity()
    print(f"[Neo4j] Connected to {args.neo4j_uri}")
    return driver


def wipe_neo4j(driver):
    print("[Neo4j] Wiping all Contact and Appointment nodes …")
    with driver.session() as s:
        s.run("MATCH (n:Contact) DETACH DELETE n")
        s.run("MATCH (n:Appointment) DETACH DELETE n")
    print("[Neo4j] Done.")


def neo4j_counts(driver):
    with driver.session() as s:
        c = s.run("MATCH (n:Contact) RETURN count(n) AS c").single()["c"]
        a = s.run("MATCH (n:Appointment) RETURN count(n) AS c").single()["c"]
    print(f"\n[Neo4j] Current node counts:")
    print(f"  Contact     {c:6d}")
    print(f"  Appointment {a:6d}")


# ---------------------------------------------------------------------------
# Contact migration
# ---------------------------------------------------------------------------
CONTACT_CREATE = """
MERGE (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
ON CREATE SET
  c.syncId            = $syncId,
  c.displayName       = $displayName,
  c.givenName         = $givenName,
  c.middleName        = $middleName,
  c.familyName        = $familyName,
  c.namePrefix        = $namePrefix,
  c.nameSuffix        = $nameSuffix,
  c.phoneticGivenName = $phoneticGivenName,
  c.phoneticMiddleName= $phoneticMiddleName,
  c.phoneticFamilyName= $phoneticFamilyName,
  c.notes             = $notes,
  c.lastUpdatedAt     = $lastUpdatedAt,
  c.createdAt         = $createdAt
ON MATCH SET
  c.displayName       = $displayName,
  c.lastUpdatedAt     = $lastUpdatedAt
RETURN c.syncId AS syncId
"""

PHONE_CREATE = """
MATCH (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
CREATE (p:Phone {number: $number, type: $type, label: $label})
CREATE (c)-[:HAS_PHONE]->(p)
"""

EMAIL_CREATE = """
MATCH (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
CREATE (e:Email {address: $address, type: $type, label: $label})
CREATE (c)-[:HAS_EMAIL]->(e)
"""

ADDRESS_CREATE = """
MATCH (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
CREATE (a:Address {street: $street, city: $city, region: $region, postCode: $postCode, country: $country, type: $type, label: $label})
CREATE (c)-[:HAS_ADDRESS]->(a)
"""

ORG_CREATE = """
MATCH (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
CREATE (o:Organization {company: $company, title: $title, department: $department})
CREATE (c)-[:HAS_ORGANIZATION]->(o)
"""

IM_CREATE = """
MATCH (c:Contact {lookupKey: $lookupKey, accountName: $accountName})
CREATE (i:InstantMessenger {handle: $handle, protocol: $protocol, customProtocol: $customProtocol})
CREATE (c)-[:HAS_IM]->(i)
"""


def migrate_contacts(mysql_conn, driver):
    cur = mysql_conn.cursor()

    contacts = fetch_all(cur, "SELECT * FROM contacts")
    phones   = fetch_all(cur, "SELECT * FROM contact_phones")
    emails   = fetch_all(cur, "SELECT * FROM contact_emails")
    addrs    = fetch_all(cur, "SELECT * FROM contact_addresses")
    orgs     = fetch_all(cur, "SELECT * FROM contact_organizations")
    ims      = fetch_all(cur, "SELECT * FROM contact_instant_messengers")
    cur.close()

    # Index children by contact_id
    def index_by(rows, key):
        d = {}
        for r in rows:
            d.setdefault(r[key], []).append(r)
        return d

    phones_by   = index_by(phones, "contact_id")
    emails_by   = index_by(emails, "contact_id")
    addrs_by    = index_by(addrs,  "contact_id")
    orgs_by     = index_by(orgs,   "contact_id")
    ims_by      = index_by(ims,    "contact_id")

    total = len(contacts)
    print(f"\n[Migrate] Contacts: {total} rows …")

    with driver.session() as s:
        for i, c in enumerate(contacts, 1):
            cid       = c["id"]
            lookup    = c["lookup_key"]
            account   = c["account_name"]
            sync_id   = str(uuid.uuid4())

            s.run(CONTACT_CREATE, {
                "syncId":             sync_id,
                "lookupKey":          lookup,
                "accountName":        account,
                "displayName":        c.get("display_name"),
                "givenName":          c.get("given_name"),
                "middleName":         c.get("middle_name"),
                "familyName":         c.get("family_name"),
                "namePrefix":         c.get("name_prefix"),
                "nameSuffix":         c.get("name_suffix"),
                "phoneticGivenName":  c.get("phonetic_given_name"),
                "phoneticMiddleName": c.get("phonetic_middle_name"),
                "phoneticFamilyName": c.get("phonetic_family_name"),
                "notes":              c.get("notes"),
                "lastUpdatedAt":      _to_ms(c.get("last_updated_at")),
                "createdAt":          _to_ms(c.get("created_at")),
            })

            for p in phones_by.get(cid, []):
                s.run(PHONE_CREATE, {"lookupKey": lookup, "accountName": account,
                                     "number": p.get("number"), "type": p.get("type"), "label": p.get("label")})
            for e in emails_by.get(cid, []):
                s.run(EMAIL_CREATE, {"lookupKey": lookup, "accountName": account,
                                     "address": e.get("address"), "type": e.get("type"), "label": e.get("label")})
            for a in addrs_by.get(cid, []):
                s.run(ADDRESS_CREATE, {"lookupKey": lookup, "accountName": account,
                                       "street": a.get("street"), "city": a.get("city"), "region": a.get("region"),
                                       "postCode": a.get("post_code"), "country": a.get("country"),
                                       "type": a.get("type"), "label": a.get("label")})
            for o in orgs_by.get(cid, []):
                s.run(ORG_CREATE, {"lookupKey": lookup, "accountName": account,
                                   "company": o.get("company"), "title": o.get("title"), "department": o.get("department")})
            for im in ims_by.get(cid, []):
                s.run(IM_CREATE, {"lookupKey": lookup, "accountName": account,
                                  "handle": im.get("handle"), "protocol": im.get("protocol"),
                                  "customProtocol": im.get("custom_protocol")})

            if i % 50 == 0 or i == total:
                print(f"  {i}/{total}", end="\r")

    print(f"\n[Migrate] Contacts done.")


# ---------------------------------------------------------------------------
# Appointment migration
# ---------------------------------------------------------------------------
APPOINTMENT_CREATE = """
MERGE (a:Appointment {syncId: $syncId, accountName: $accountName})
ON CREATE SET
  a.deviceId           = $deviceId,
  a.title              = $title,
  a.description        = $description,
  a.dtStart            = $dtStart,
  a.dtEnd              = $dtEnd,
  a.duration           = $duration,
  a.allDay             = $allDay,
  a.timezone           = $timezone,
  a.rrule              = $rrule,
  a.location           = $location,
  a.organizer          = $organizer,
  a.calendarName       = $calendarName,
  a.calendarAccountType= $calendarAccountType,
  a.calendarAccountName= $calendarAccountName,
  a.calendarColor      = $calendarColor,
  a.lastUpdatedAt      = $lastUpdatedAt,
  a.createdAt          = $createdAt
ON MATCH SET
  a.lastUpdatedAt = $lastUpdatedAt
RETURN a.syncId AS syncId
"""

ATTENDEE_CREATE = """
MATCH (a:Appointment {syncId: $syncId, accountName: $accountName})
CREATE (at:Attendee {name: $name, email: $email, type: $type, status: $status, contactLookupKey: $contactLookupKey})
CREATE (a)-[:HAS_ATTENDEE]->(at)
"""


def migrate_appointments(mysql_conn, driver):
    cur = mysql_conn.cursor()
    appointments = fetch_all(cur, "SELECT * FROM appointments")
    attendees    = fetch_all(cur, "SELECT * FROM appointment_attendees")
    cur.close()

    attendees_by = {}
    for att in attendees:
        attendees_by.setdefault(att["appointment_id"], []).append(att)

    total = len(appointments)
    print(f"\n[Migrate] Appointments: {total} rows …")

    with driver.session() as s:
        for i, ap in enumerate(appointments, 1):
            ap_id    = ap["id"]  # existing string PK (may be UUID-like or deviceId)
            account  = ap["account_name"]
            sync_id  = str(uuid.uuid4())  # new UUID — see README for sync implications

            s.run(APPOINTMENT_CREATE, {
                "syncId":              sync_id,
                "accountName":         account,
                "deviceId":            ap.get("device_id"),
                "title":               ap.get("title"),
                "description":         ap.get("description"),
                "dtStart":             _to_ms(ap.get("dt_start")),
                "dtEnd":               _to_ms(ap.get("dt_end")),
                "duration":            ap.get("duration"),
                "allDay":              bool(ap.get("all_day", False)),
                "timezone":            ap.get("timezone"),
                "rrule":               ap.get("rrule"),
                "location":            ap.get("location"),
                "organizer":           ap.get("organizer"),
                "calendarName":        ap.get("calendar_name"),
                "calendarAccountType": ap.get("calendar_account_type"),
                "calendarAccountName": ap.get("calendar_account_name"),
                "calendarColor":       ap.get("calendar_color"),
                "lastUpdatedAt":       _to_ms(ap.get("last_updated_at")),
                "createdAt":           _to_ms(ap.get("created_at")),
            })

            for att in attendees_by.get(ap_id, []):
                s.run(ATTENDEE_CREATE, {
                    "syncId":           sync_id,
                    "accountName":      account,
                    "name":             att.get("name"),
                    "email":            att.get("email"),
                    "type":             att.get("type"),
                    "status":           att.get("status"),
                    "contactLookupKey": att.get("contact_lookup_key"),
                })

            if i % 50 == 0 or i == total:
                print(f"  {i}/{total}", end="\r")

    print(f"\n[Migrate] Appointments done.")


# ---------------------------------------------------------------------------
# Utility
# ---------------------------------------------------------------------------
def _to_ms(value) -> int | None:
    """Convert MySQL datetime / timestamp to Unix ms."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return int(value.timestamp() * 1000)
    return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    args = parse_args()

    # Connect
    mysql_conn = mysql_connect(args)
    driver     = neo4j_connect(args)

    # Always print MySQL counts
    print_mysql_counts(mysql_conn)
    neo4j_counts(driver)

    if not args.migrate_data:
        print("\n[Info] Dry-run complete. Use --migrate-data to actually migrate.")
        print("[Info] See migration/README.md for important notes before migrating.")
        mysql_conn.close()
        driver.close()
        return

    print("\n[WARNING] --migrate-data is set.")
    print("  New syncIds will be generated for all contacts/appointments.")
    print("  On the first sync after migration, the phone will re-upload all data.")
    print("  This may temporarily result in duplicate entries if the server already")
    print("  contains migrated data AND phone-uploaded data. See README for details.\n")

    if args.wipe_neo4j:
        wipe_neo4j(driver)

    migrate_contacts(mysql_conn, driver)
    migrate_appointments(mysql_conn, driver)

    print("\n[Done] Migration complete.")
    neo4j_counts(driver)
    print("\n[Next step] Register the account in the app (new password required).")
    print("  On first sync: phone will upload all contacts/appointments with its own syncIds.")
    print("  If you used --wipe-neo4j beforehand, no duplicates will occur.")

    mysql_conn.close()
    driver.close()


if __name__ == "__main__":
    main()
