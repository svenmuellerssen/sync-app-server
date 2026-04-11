-- Migration: contacts PK von lookup_key (VARCHAR) auf id (BIGINT AUTO_INCREMENT)
-- Alle 5 Kind-Tabellen: lookup_key-Spalte → contact_id (BIGINT FK)
--
-- Voraussetzung: App-Container vorher stoppen (cluster-nas.bat stop)
-- Danach: cluster-nas.bat deploy  ODER  cluster-nas.bat start

-- =========================================================
-- 0. Vorhandene FK-Constraints auf Kind-Tabellen droppen
-- =========================================================
ALTER TABLE contact_phones       DROP FOREIGN KEY FKetnf387kl0gfpqykmut826jih;
ALTER TABLE contact_emails       DROP FOREIGN KEY FKm5v3gdeyoww80hygxq928ubt1;
ALTER TABLE contact_addresses    DROP FOREIGN KEY FKlqnbpnwvb070m18ysno0pbw2k;
ALTER TABLE contact_organizations DROP FOREIGN KEY FKe15yy1ospeu1xbxd7gscg4bxt;
ALTER TABLE contact_instant_messengers DROP FOREIGN KEY FKee6dxdj6tw0p4s6e71x91paeb;

-- =========================================================
-- 1. contacts: neuen BIGINT PK hinzufügen, lookup_key bleibt UNIQUE
-- =========================================================
ALTER TABLE contacts
  DROP PRIMARY KEY,
  ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;

ALTER TABLE contacts
  ADD CONSTRAINT uq_contacts_lookup_key UNIQUE (lookup_key);

-- =========================================================
-- 2. contact_phones
-- =========================================================
ALTER TABLE contact_phones ADD COLUMN contact_id BIGINT;

UPDATE contact_phones p
  JOIN contacts c ON c.lookup_key = p.lookup_key
  SET p.contact_id = c.id;

ALTER TABLE contact_phones MODIFY COLUMN contact_id BIGINT NOT NULL;
ALTER TABLE contact_phones DROP COLUMN lookup_key;

-- =========================================================
-- 3. contact_emails
-- =========================================================
ALTER TABLE contact_emails ADD COLUMN contact_id BIGINT;

UPDATE contact_emails p
  JOIN contacts c ON c.lookup_key = p.lookup_key
  SET p.contact_id = c.id;

ALTER TABLE contact_emails MODIFY COLUMN contact_id BIGINT NOT NULL;
ALTER TABLE contact_emails DROP COLUMN lookup_key;

-- =========================================================
-- 4. contact_addresses
-- =========================================================
ALTER TABLE contact_addresses ADD COLUMN contact_id BIGINT;

UPDATE contact_addresses p
  JOIN contacts c ON c.lookup_key = p.lookup_key
  SET p.contact_id = c.id;

ALTER TABLE contact_addresses MODIFY COLUMN contact_id BIGINT NOT NULL;
ALTER TABLE contact_addresses DROP COLUMN lookup_key;

-- =========================================================
-- 5. contact_organizations
-- =========================================================
ALTER TABLE contact_organizations ADD COLUMN contact_id BIGINT;

UPDATE contact_organizations p
  JOIN contacts c ON c.lookup_key = p.lookup_key
  SET p.contact_id = c.id;

ALTER TABLE contact_organizations MODIFY COLUMN contact_id BIGINT NOT NULL;
ALTER TABLE contact_organizations DROP COLUMN lookup_key;

-- =========================================================
-- 6. contact_instant_messengers
-- =========================================================
ALTER TABLE contact_instant_messengers ADD COLUMN contact_id BIGINT;

UPDATE contact_instant_messengers p
  JOIN contacts c ON c.lookup_key = p.lookup_key
  SET p.contact_id = c.id;

ALTER TABLE contact_instant_messengers MODIFY COLUMN contact_id BIGINT NOT NULL;
ALTER TABLE contact_instant_messengers DROP COLUMN lookup_key;
