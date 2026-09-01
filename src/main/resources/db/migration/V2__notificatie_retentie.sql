-- V2: Notificatie-retentie loskoppelen van callback-afhandeling.
-- laatste_status_update markeert wanneer de status van een Notificatie voor het laatst is
-- gewijzigd (o.a. door een NotifyNL-callback); de retentiejob gebruikt dit veld om te bepalen
-- welke rijen mogen worden opgeruimd, los van of/hoe een callback naar de Dienstverlener verliep.
-- Geen backfill van bestaande rijen nodig: dit component draait nog niet live.

ALTER TABLE notificatie ADD COLUMN laatste_status_update timestamp(6) with time zone NOT NULL DEFAULT now();
CREATE INDEX idx_notificatie_laatste_status_update ON notificatie (laatste_status_update);

-- status was varchar(17) — precies de lengte van 'PERMANENT_FAILURE'. Trek gelijk met de nieuwe
-- notificatie_status-tabel hieronder (varchar(32)) zodat beide dezelfde waarden kunnen bevatten.
ALTER TABLE notificatie ALTER COLUMN status TYPE varchar(32);

-- Geschiedenis van statusovergangen per notificatie (Notificatie#registreerStatus legt hier
-- telkens een rij in vast). Een @ElementCollection-tabel: geen eigen id, de rij heeft geen
-- identiteit los van zijn Notificatie. volgnummer is de @OrderColumn (behoudt de volgorde bij
-- herladen). ON DELETE CASCADE is verdediging in de diepte — Hibernate ruimt deze rijen bij een
-- bulk-delete (zoals de retentiejob) al zelf op; de FK dekt verwijdering buiten Hibernate om.
CREATE TABLE notificatie_status (
    notificatie_id uuid NOT NULL REFERENCES notificatie(id) ON DELETE CASCADE,
    volgnummer integer NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED'
    )),
    tijdstip timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (notificatie_id, volgnummer)
);
