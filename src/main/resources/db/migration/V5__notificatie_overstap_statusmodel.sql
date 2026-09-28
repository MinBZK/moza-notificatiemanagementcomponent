-- V5: het schrijfpad stapt over op het statusmodel uit V4. Bestaande rijen krijgen een status, een
-- poging en hun geschiedenis als events; daarna bewaakt een trigger dat elke versie een event heeft.
-- notificatie_status en external_reference blijven staan tot een volgende migratie.

CREATE TEMP TABLE oude_status_afbeelding (
    oud varchar(32) PRIMARY KEY,
    notificatie_status varchar(32) NOT NULL,
    reden varchar(32),
    poging_status varchar(32)
) ON COMMIT DROP;

INSERT INTO oude_status_afbeelding (oud, notificatie_status, reden, poging_status) VALUES
    ('CREATED', 'AANGENOMEN', NULL, NULL),
    ('SENDING', 'VERZONDEN', NULL, 'VERZONDEN'),
    ('DELIVERED', 'BEZORGD', NULL, 'BEZORGD'),
    ('PERMANENT_FAILURE', 'NIET_BEZORGBAAR', 'ONBEREIKBAAR', 'PERMANENT_MISLUKT'),
    ('TEMPORARY_FAILURE', 'NIET_BEZORGBAAR', 'ONBEREIKBAAR', 'TIJDELIJK_MISLUKT'),
    ('TECHNICAL_FAILURE', 'TECHNISCH_MISLUKT', 'TECHNISCH', 'TECHNISCH_MISLUKT'),
    ('ONBEKEND', 'BEZORGSTATUS_ONBEKEND', NULL, 'ONBEKEND');

-- ONBEKEND telt niet mee: in het nieuwe model verandert een onbekende status niets, dus de status
-- komt uit het laatste geschiedenisrecord met een bekende status. Dat volgnummer wordt de versie,
-- zodat het laatste event en de versie gelijk lopen.
CREATE TEMP TABLE laatste_bekende_status ON COMMIT DROP AS
SELECT DISTINCT ON (s.notificatie_id) s.notificatie_id, s.status, s.volgnummer
FROM notificatie_status s
WHERE s.status <> 'ONBEKEND'
ORDER BY s.notificatie_id, s.volgnummer DESC;

UPDATE notificatie n
SET status = a.notificatie_status,
    reden = a.reden,
    versie = l.volgnummer
FROM laatste_bekende_status l
JOIN oude_status_afbeelding a ON a.oud = l.status
WHERE l.notificatie_id = n.id
  AND n.status IS NULL;

-- Vangnet voor een rij zonder bruikbare geschiedenis; V2 gaf elke rij minstens één record.
UPDATE notificatie n
SET status = a.notificatie_status,
    reden = a.reden
FROM oude_status_afbeelding a
WHERE a.oud = n.laatste_status
  AND n.status IS NULL;

INSERT INTO poging (id, notificatie_id, nummer, status, notify_id, verzonden_op, receipt_tijdstip)
SELECT gen_random_uuid(), n.id, 1, COALESCE(a.poging_status, 'VERZONDEN'), n.external_reference,
       (SELECT min(s.tijdstip) FROM notificatie_status s WHERE s.notificatie_id = n.id AND s.status = 'SENDING'),
       (SELECT max(s.tijdstip) FROM notificatie_status s
         WHERE s.notificatie_id = n.id
           AND s.status IN ('DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE'))
FROM notificatie n
LEFT JOIN laatste_bekende_status l ON l.notificatie_id = n.id
LEFT JOIN oude_status_afbeelding a ON a.oud = l.status
WHERE n.external_reference IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM poging p WHERE p.notificatie_id = n.id);

INSERT INTO event (tijdstip, notificatie_id, volgnummer, van, naar, reden)
SELECT s.geregistreerd, s.notificatie_id, s.volgnummer,
       lag(a.notificatie_status) OVER (PARTITION BY s.notificatie_id ORDER BY s.volgnummer),
       a.notificatie_status, a.reden
FROM notificatie_status s
JOIN oude_status_afbeelding a ON a.oud = s.status
WHERE s.status <> 'ONBEKEND'
  AND NOT EXISTS (SELECT 1 FROM event e WHERE e.notificatie_id = s.notificatie_id);

ALTER TABLE notificatie ALTER COLUMN status SET NOT NULL;
ALTER TABLE notificatie ALTER COLUMN laatste_status DROP NOT NULL;

-- Een nieuwe notificatie begint op AANGENOMEN; een gewijzigde versie moet een toegestane overgang
-- zijn. In beide gevallen moet in dezelfde transactie een event met die versie als volgnummer zijn
-- geschreven. Deferred, omdat het event na de rij wordt weggeschreven.
CREATE FUNCTION controleer_notificatie_overgang() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'AANGENOMEN' THEN
            RAISE EXCEPTION 'Notificatie % begint op %, niet op AANGENOMEN', NEW.id, NEW.status
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.versie = OLD.versie THEN
        IF NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION 'Status van notificatie % gewijzigd zonder nieuwe versie', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;

        RETURN NULL;
    ELSIF NOT EXISTS (SELECT 1 FROM toegestane_overgang WHERE van = OLD.status AND naar = NEW.status) THEN
        RAISE EXCEPTION 'Overgang van % naar % is niet toegestaan (notificatie %)', OLD.status, NEW.status, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM event
                   WHERE notificatie_id = NEW.id AND volgnummer = NEW.versie AND xid = pg_current_xact_id()) THEN
        RAISE EXCEPTION 'Versie % van notificatie % heeft geen event in deze transactie', NEW.versie, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER notificatie_overgang
    AFTER INSERT OR UPDATE ON notificatie
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION controleer_notificatie_overgang();
