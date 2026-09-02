-- V2: Notificatie-retentie loskoppelen van callback-afhandeling.
-- status en laatste_status_update van een Notificatie leven alleen in notificatie_status (het
-- laatste record daarvan is de single source of truth). De retentiejob gebruikt dat laatste
-- tijdstip om te bepalen welke rijen mogen worden opgeruimd, los van of/hoe een callback naar de
-- Dienstverlener verliep.
-- Geen backfill van bestaande rijen nodig: dit component draait nog niet live.

ALTER TABLE notificatie DROP COLUMN status;

-- Geschiedenis van statusovergangen per notificatie (Notificatie#registreerStatus legt hier
-- telkens een rij in vast). Een @ElementCollection-tabel: geen eigen id, de rij heeft geen
-- identiteit los van zijn Notificatie. Geordend op tijdstip (zie @OrderBy op Notificatie). Bewust
-- geen PK op (notificatie_id, tijdstip): niets garandeert dat tijdstip uniek is per notificatie
-- (timestamp(6) heeft een eindige resolutie), dus een unieke constraint hierop zou een insert hard
-- laten falen op een randgeval waar de rest van de code al tegen bestand is (zie de deduplicatie in
-- NotificatieRetentieScheduler#verwijderBatch). Twee losse indexen dekken de twee toegangspatronen
-- van de retentiejob: (notificatie_id, tijdstip) voor de per-notificatie MAX-subquery, tijdstip
-- alleen voor de globale "ouder dan grens"-scan. ON DELETE CASCADE is verdediging in de diepte —
-- Hibernate ruimt deze rijen bij een bulk-delete (zoals de retentiejob) al zelf op; de FK dekt
-- verwijdering buiten Hibernate om.
CREATE TABLE notificatie_status (
    notificatie_id uuid NOT NULL REFERENCES notificatie(id) ON DELETE CASCADE,
    status varchar(32) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED'
    )),
    tijdstip timestamp(6) with time zone NOT NULL
);
CREATE INDEX idx_notificatie_status_notificatie_id_tijdstip ON notificatie_status (notificatie_id, tijdstip);
CREATE INDEX idx_notificatie_status_tijdstip ON notificatie_status (tijdstip);
