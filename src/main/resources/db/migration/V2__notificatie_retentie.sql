-- V2: Notificatie-retentie loskoppelen van callback-afhandeling.
-- Elke statusovergang krijgt een eigen record in notificatie_status; notificatie.status en
-- notificatie.aangemaakt vervallen. De retentiejob selecteert op notificatie.laatste_status_update,
-- een projectie van het laatste record in die geschiedenis, los van of/hoe een callback naar de
-- Dienstverlener verliep.

-- Optimistic-locking-kolom (Notificatie#versie, @Version). De statusgeschiedenis is een Hibernate-
-- bag: elke toevoeging herschrijft alle statusregels van de notificatie, dus zonder versiecontrole
-- overschrijft de laatste van twee gelijktijdige callbacks de statusregel van de eerste zonder
-- signaal. DEFAULT 0 zodat bestaande rijen (previewclusters, %dev, lokale volumes) blijven werken.
ALTER TABLE notificatie ADD COLUMN versie bigint NOT NULL DEFAULT 0;

-- Geschiedenis van statusovergangen per notificatie (Notificatie#registreerStatus legt hier
-- telkens een rij in vast). Een @ElementCollection-tabel: geen eigen id, de rij heeft geen
-- identiteit los van zijn Notificatie. Geordend op tijdstip (zie @OrderBy op Notificatie). Bewust
-- geen PK op (notificatie_id, tijdstip): niets garandeert dat tijdstip uniek is per notificatie
-- (timestamp(6) heeft een eindige resolutie), dus een unieke constraint hierop zou een insert hard
-- laten falen op een randgeval dat verder nergens iets breekt. De index dekt het enige
-- toegangspatroon op deze tabel: de statusgeschiedenis van één notificatie op tijdstip inlezen.
-- ON DELETE CASCADE is verdediging in de diepte: Hibernate ruimt deze rijen bij een bulk-delete
-- (zoals de retentiejob) al zelf op; de FK dekt verwijdering buiten Hibernate om.
CREATE TABLE notificatie_status (
    notificatie_id uuid NOT NULL REFERENCES notificatie(id) ON DELETE CASCADE,
    status varchar(32) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
    )),
    tijdstip timestamp(6) with time zone NOT NULL
);
CREATE INDEX idx_notificatie_status_notificatie_id_tijdstip ON notificatie_status (notificatie_id, tijdstip);

-- Backfill vóór de DROP COLUMNs hieronder: dit component draait weliswaar nog niet live in het
-- release-cluster, maar ZAD-PR-previewclusters, %dev en lokale Podman-instanties hebben persistente
-- volumes waar wél al rijen kunnen staan. Elke bestaande notificatie krijgt zo alsnog exact één
-- geschiedenisrecord (zijn huidige status op zijn aanmaaktijdstip) i.p.v. stilzwijgend zonder
-- geschiedenis te blijven zitten, zie Notificatie#eersteStatus voor wat dat anders oplevert.
-- Kanttekening: aangemaakt is het enige tijdstip dat V1 vastlegde, dus voor bestaande rijen loopt de
-- bewaartermijn vanaf de aanmaak en niet vanaf hun laatste statuswijziging. Een rij van 45 dagen oud
-- die gisteren nog DELIVERED werd, is daarmee direct opruimbaar. Aanvaardbaar omdat het alleen om
-- wegwerpomgevingen gaat (zie hierboven); in het release-cluster staat nog niets.
INSERT INTO notificatie_status (notificatie_id, status, tijdstip)
SELECT id, status, aangemaakt FROM notificatie;

-- Projectie van het laatste geschiedenisrecord (Notificatie#laatsteStatus / #laatsteStatusUpdate).
-- De retentiejob selecteert hierop: één geïndexeerde bereikscan over notificatie, i.p.v. een scan
-- over notificatie_status met per notificatie een MAX-subquery. Eerst nullable toegevoegd zodat
-- bestaande rijen gevuld kunnen worden, daarna pas NOT NULL.
ALTER TABLE notificatie ADD COLUMN laatste_status varchar(32);
ALTER TABLE notificatie ADD COLUMN laatste_status_update timestamp(6) with time zone;

UPDATE notificatie SET laatste_status = status, laatste_status_update = aangemaakt;

ALTER TABLE notificatie ALTER COLUMN laatste_status SET NOT NULL;
ALTER TABLE notificatie ALTER COLUMN laatste_status_update SET NOT NULL;
ALTER TABLE notificatie ADD CONSTRAINT chk_notificatie_laatste_status CHECK (laatste_status IN (
    'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
));
CREATE INDEX idx_notificatie_laatste_status_update ON notificatie (laatste_status_update);

ALTER TABLE notificatie DROP COLUMN status;
ALTER TABLE notificatie DROP COLUMN aangemaakt;
