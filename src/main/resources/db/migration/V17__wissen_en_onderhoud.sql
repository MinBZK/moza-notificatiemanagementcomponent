-- V17: wistaak en onderhoudstaak uit ADR 0024 vervangen de retentiejob. Wistermijn en bewaartermijn
-- lopen vanaf de terminale status (terminaal_op) in plaats van vanaf de laatste overgang, en het
-- eventlog krijgt bereikpartities die de onderhoudstaak aanmaakt en opruimt. V16 hoort bij een andere
-- story.

-- Wanneer de notificatie terminaal werd; leeg zolang ze dat niet is. Voor bestaande terminale rijen is
-- de laatste overgang de terminale.
ALTER TABLE notificatie ADD COLUMN terminaal_op timestamp(6) with time zone;

UPDATE notificatie
   SET terminaal_op = laatste_status_update
 WHERE status IN ('DEFINITIEF_BEZORGD', 'NIET_BEZORGBAAR', 'TECHNISCH_MISLUKT', 'BEZORGSTATUS_ONBEKEND',
                  'VERLOPEN', 'GEANNULEERD');

-- De update zet deferred triggercontroles klaar (zelfde versie en status, dus die slagen), en zolang die
-- openstaan weigert PostgreSQL DDL op notificatie.
SET CONSTRAINTS notificatie_overgang IMMEDIATE;

-- Geen CONCURRENTLY: de retentiejob hield het register tot nu toe op zeven dagen, dus de tabel is klein.
CREATE INDEX notificatie_terminaal_op_idx ON notificatie (terminaal_op) WHERE terminaal_op IS NOT NULL;

-- Een wistaak voor elke terminale notificatie die nog een sleutel heeft; de taak wacht zelf de
-- wistermijn vanaf terminaal_op af.
INSERT INTO taak (soort, dv_id, notificatie_id, due, status)
SELECT 'WISSEN', dv_id, id, terminaal_op, 'OPEN'
  FROM notificatie
 WHERE terminaal_op IS NOT NULL
   AND sleutel_gewrapt IS NOT NULL
ON CONFLICT DO NOTHING;

-- Niets leest ze nog; de index van V3 gaat mee.
ALTER TABLE notificatie
    DROP COLUMN laatste_status,
    DROP COLUMN laatste_status_update;

-- Leeg betekent: de default uit de configuratie (nmc.feed.max-cursorleeftijd).
ALTER TABLE dienstverlener ADD COLUMN max_cursorleeftijd interval CHECK (max_cursorleeftijd > interval '0');

-- Wanneer de leverpositie voor het laatst verschoof. bijgewerkt_op verschuift ook bij een mislukte
-- levering en zegt dus niet hoe oud de positie is.
ALTER TABLE webhookpositie ADD COLUMN geleverd_op timestamp(6) with time zone;

UPDATE webhookpositie SET geleverd_op = bijgewerkt_op WHERE xid IS NOT NULL;

-- De default-partitie wordt de eerste bereikpartitie, tot de eerstvolgende transactie-id; alle rijen
-- erin liggen daaronder. Een nieuwe, lege default-partitie vangt op wat buiten de bereiken valt. Zo
-- hoeft de onderhoudstaak bij het aanmaken van een bereik nooit rijen uit de default-partitie te
-- verplaatsen: ze begint elk nieuw bereik boven de uitgedeelde transactie-ids.
DO $$
DECLARE
    tot text := pg_snapshot_xmax(pg_current_snapshot())::text;
BEGIN
    ALTER TABLE event DETACH PARTITION event_standaard;
    ALTER TABLE event_standaard RENAME TO event_0;
    EXECUTE format('ALTER TABLE event ATTACH PARTITION event_0 FOR VALUES FROM (%L) TO (%L)', '0', tot);
    CREATE TABLE event_standaard PARTITION OF event DEFAULT;
END;
$$;

-- De onderhoudstaak per systeem; daarna plant ze zichzelf steeds opnieuw.
INSERT INTO taak (soort, due, status) VALUES ('ONDERHOUD', now(), 'OPEN');

-- Elke lopende schrijftransactie houdt het watermerk van de feed vast; deze timeouts begrenzen hoelang.
-- Ze gelden voor nieuwe sessies van deze rol op deze database, dus ook voor Flyway: een migratie die
-- langer duurt zet ze voor de eigen transactie uit met SET LOCAL.
DO $$
BEGIN
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET statement_timeout = %L',
                   current_user, current_database(), '30s');
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET transaction_timeout = %L',
                   current_user, current_database(), '1min');
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET idle_in_transaction_session_timeout = %L',
                   current_user, current_database(), '30s');
END;
$$;
