-- V3: gebeurtenistijd en registratietijd uit elkaar halen in de statusgeschiedenis.
--
-- notificatie_status.tijdstip was tot nu toe het moment waarop de NMC een status verwerkte. Voor
-- CREATED en SENDING (registraties van de NMC zelf) klopt dat, maar voor een delivery receipt van
-- NotifyNL is het de aankomsttijd en niet het moment waarop de status ontstond. NotifyNL levert dat
-- moment wel: completed_at ("the last time the status was updated"), met sent_at en created_at als
-- terugval (zie src/main/resources/openapi/notifynl_api.yaml, EmailCallbackRequest). Voor een
-- afleverbewijs is de gebeurtenistijd de juiste; NotifyNL herhaalt een callback tot 5x met 5 minuten
-- ertussen, dus aankomsttijd en gebeurtenistijd kunnen tientallen minuten uiteenlopen.
--
-- tijdstip houdt daarom voortaan de gebeurtenistijd vast en geregistreerd de klok van de NMC.

ALTER TABLE notificatie_status ADD COLUMN geregistreerd timestamp(6) with time zone;

-- Bestaande rijen zijn allemaal met de klok van de NMC geschreven (V2 kende geen andere bron), dus
-- daar zijn gebeurtenis- en registratietijd per definitie hetzelfde moment.
UPDATE notificatie_status SET geregistreerd = tijdstip;

ALTER TABLE notificatie_status ALTER COLUMN geregistreerd SET NOT NULL;

-- De geschiedenis wordt voortaan op geregistreerd geordend (@OrderBy op Notificatie#statusGeschiedenis):
-- de eigen klok is monotoon, die van NotifyNL niet. Ordenen op tijdstip zou een receipt met een oude
-- completed_at vóór de aanmaakstatus laten sorteren. De index dekt daarmee hetzelfde toegangspatroon
-- als de oude: de statusgeschiedenis van één notificatie op volgorde inlezen.
DROP INDEX idx_notificatie_status_notificatie_id_tijdstip;
CREATE INDEX idx_notificatie_status_notificatie_id_geregistreerd
    ON notificatie_status (notificatie_id, geregistreerd);

-- Projectie van de gebeurtenistijd van de status in laatste_status (Notificatie#laatsteStatusTijdstip).
-- Stond tot nu toe in laatste_status_update; die kolom krijgt hieronder een andere betekenis.
ALTER TABLE notificatie ADD COLUMN laatste_status_tijdstip timestamp(6) with time zone;

UPDATE notificatie SET laatste_status_tijdstip = laatste_status_update;

ALTER TABLE notificatie ALTER COLUMN laatste_status_tijdstip SET NOT NULL;

-- laatste_status_update is vanaf nu de registratietijd: het moment waarop de NMC voor het laatst een
-- status vastlegde, op de eigen klok. De retentiejob selecteert hierop (idx_notificatie_laatste_status_update,
-- V2), en die moet niet afhangen van de klok van NotifyNL: een receipt met een scheve of oude
-- completed_at zou een notificatie anders meteen opruimbaar maken. Bestaande rijen houden hun huidige
-- waarde — die is met de klok van de NMC geschreven en dus al een registratietijd.
