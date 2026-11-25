-- V16: de aanname herkent een herhaalde aanvraag aan de eigen referentie van de Dienstverlener (dvRef).
-- De referentie staat niet in platte tekst op de rij: dv_ref_hmac is een HMAC over dienstverlener en
-- referentie, payload_hash een HMAC over de inhoud van de aanvraag, beide met de pepper van die versie.
-- Leeg voor aanvragen zonder referentie.

ALTER TABLE notificatie
    ADD COLUMN dv_ref_hmac bytea,
    ADD COLUMN payload_hash bytea,
    ADD COLUMN pepper_versie smallint,
    ADD CONSTRAINT notificatie_dv_ref_check
        CHECK ((dv_ref_hmac IS NULL) = (payload_hash IS NULL) AND (dv_ref_hmac IS NULL) = (pepper_versie IS NULL));

-- Per Dienstverlener hoort bij een referentie met dezelfde inhoud ten hoogste één notificatie. De index
-- dient ook het zoeken op referentie. Zonder CONCURRENTLY, omdat de database voor de eerste
-- productiedata opnieuw wordt opgebouwd.
CREATE UNIQUE INDEX notificatie_dv_ref_idx
    ON notificatie (dv_id, dv_ref_hmac, payload_hash)
    WHERE dv_ref_hmac IS NOT NULL;
