-- V2: statusgeschiedenis per notificatie.
-- Elke statusovergang krijgt een eigen record in notificatie_status; op notificatie staat een
-- projectie van het laatste record. notificatie.status en notificatie.aangemaakt vervallen.

-- Optimistic locking (Notificatie#versie). Zonder versiecontrole overschrijft de laatste van twee
-- gelijktijdige delivery receipts de eerste zonder signaal. DEFAULT 0 voor bestaande rijen.
ALTER TABLE notificatie ADD COLUMN versie bigint NOT NULL DEFAULT 0;

-- tijdstip is wanneer de status ontstond, op de klok van de bron (voor een delivery receipt
-- NotifyNL's completed_at); geregistreerd is wanneer de NMC hem vastlegde, op de eigen klok.
-- Die lopen uiteen omdat NotifyNL een mislukte callback tot 5x met 5 minuten ertussen herhaalt.
--
-- volgnummer is de @OrderColumn van Notificatie#statusGeschiedenis. Zonder die kolom is de
-- collectie voor Hibernate een bag en wordt elke toevoeging een delete-all plus reinsert. Samen met
-- notificatie_id is hij de primary key: twee gelijktijdige schrijvers die hetzelfde volgnummer
-- willen gebruiken lopen daarop stuk, ook als ze Hibernate omzeilen.
--
-- ON DELETE CASCADE dekt verwijdering buiten Hibernate om.
CREATE TABLE notificatie_status (
    notificatie_id uuid NOT NULL REFERENCES notificatie(id) ON DELETE CASCADE,
    volgnummer integer NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
    )),
    tijdstip timestamp(6) with time zone NOT NULL,
    geregistreerd timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (notificatie_id, volgnummer)
);

-- Backfill vóór de DROP COLUMNs hieronder: previewclusters, %dev en lokale volumes kunnen al rijen
-- hebben. Elke bestaande notificatie krijgt één record met zijn huidige status op zijn
-- aanmaaktijdstip, het enige tijdstip dat V1 vastlegde. volgnummer 0 omdat @OrderColumn
-- nul-gebaseerd is.
INSERT INTO notificatie_status (notificatie_id, volgnummer, status, tijdstip, geregistreerd)
SELECT id, 0, status, aangemaakt, aangemaakt FROM notificatie;

-- Status en registratietijd van het laatste geschiedenisrecord, bijgewerkt door Notificatie#registreerStatus.
-- Eerst nullable, zodat bestaande rijen gevuld kunnen worden, daarna pas NOT NULL.
ALTER TABLE notificatie ADD COLUMN laatste_status varchar(32);
ALTER TABLE notificatie ADD COLUMN laatste_status_update timestamp(6) with time zone;

UPDATE notificatie SET laatste_status = status, laatste_status_update = aangemaakt;

ALTER TABLE notificatie ALTER COLUMN laatste_status SET NOT NULL;
ALTER TABLE notificatie ALTER COLUMN laatste_status_update SET NOT NULL;
ALTER TABLE notificatie ADD CONSTRAINT chk_notificatie_laatste_status CHECK (laatste_status IN (
    'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
));

ALTER TABLE notificatie DROP COLUMN status;
ALTER TABLE notificatie DROP COLUMN aangemaakt;
