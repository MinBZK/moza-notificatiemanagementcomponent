-- V2: Notificatie-retentie loskoppelen van callback-afhandeling.
-- De status van een Notificatie — en daarmee het aanmaaktijdstip en het tijdstip van de laatste
-- statuswijziging — leeft vanaf nu alleen in notificatie_status; de kolommen notificatie.status en
-- notificatie.aangemaakt vervallen. De retentiejob gebruikt het laatste tijdstip in
-- notificatie_status om te bepalen welke rijen mogen worden opgeruimd, los van of/hoe een callback
-- naar de Dienstverlener verliep.

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
-- laten falen op een randgeval waar de rest van de code al tegen bestand is (zie de deduplicatie in
-- NotificatieRetentieScheduler#verwijderBatch). Twee losse indexen dekken de twee toegangspatronen
-- van de retentiejob: (notificatie_id, tijdstip) voor de per-notificatie MAX-subquery, tijdstip
-- alleen voor de globale "ouder dan grens"-scan. ON DELETE CASCADE is verdediging in de diepte —
-- Hibernate ruimt deze rijen bij een bulk-delete (zoals de retentiejob) al zelf op; de FK dekt
-- verwijdering buiten Hibernate om.
CREATE TABLE notificatie_status (
    notificatie_id uuid NOT NULL REFERENCES notificatie(id) ON DELETE CASCADE,
    status varchar(32) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
    )),
    tijdstip timestamp(6) with time zone NOT NULL
);
CREATE INDEX idx_notificatie_status_notificatie_id_tijdstip ON notificatie_status (notificatie_id, tijdstip);
CREATE INDEX idx_notificatie_status_tijdstip ON notificatie_status (tijdstip);

-- Backfill vóór de DROP COLUMNs hieronder: dit component draait weliswaar nog niet live in het
-- release-cluster, maar ZAD-PR-previewclusters, %dev en lokale Podman-instanties hebben persistente
-- volumes waar wél al rijen kunnen staan. Elke bestaande notificatie krijgt zo alsnog exact één
-- CREATED-geschiedenisrecord (op zijn eigen aanmaaktijdstip) i.p.v. stilzwijgend zonder geschiedenis
-- te blijven zitten — zie Notificatie#laatsteStatus voor wat dat anders oplevert.
INSERT INTO notificatie_status (notificatie_id, status, tijdstip)
SELECT id, status, aangemaakt FROM notificatie;

ALTER TABLE notificatie DROP COLUMN status;
ALTER TABLE notificatie DROP COLUMN aangemaakt;
