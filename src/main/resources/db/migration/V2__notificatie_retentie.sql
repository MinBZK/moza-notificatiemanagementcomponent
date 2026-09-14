-- V2: Notificatie-retentie loskoppelen van callback-afhandeling.
-- Elke statusovergang krijgt een eigen record in notificatie_status; notificatie.status en
-- notificatie.aangemaakt vervallen. De retentiejob selecteert op notificatie.laatste_status_update,
-- een projectie van het laatste record in die geschiedenis, los van of/hoe een callback naar de
-- Dienstverlener verliep.

-- Optimistic-locking-kolom (Notificatie#versie, @Version). Twee gelijktijdige delivery receipts voor
-- dezelfde notificatie werken allebei de projectiekolommen hieronder bij; zonder versiecontrole
-- overschrijft de laatste commit de eerste zonder signaal. De primary key op notificatie_status is
-- het vangnet daaronder (zie daar). DEFAULT 0 zodat bestaande rijen (previewclusters, %dev, lokale
-- volumes) blijven werken.
ALTER TABLE notificatie ADD COLUMN versie bigint NOT NULL DEFAULT 0;

-- Geschiedenis van statusovergangen per notificatie (Notificatie#registreerStatus legt hier
-- telkens een rij in vast). Een @ElementCollection-tabel: geen eigen id, de rij heeft geen
-- identiteit los van zijn Notificatie.
--
-- Twee tijdstippen, bewust uit elkaar gehouden:
--   tijdstip      wanneer de status ontstond, op de klok van de bron. Voor een delivery receipt is
--                 dat NotifyNL's completed_at ("the last time the status was updated"), met sent_at
--                 en created_at als terugval — zie EmailCallbackRequest in
--                 src/main/resources/openapi/notifynl_api.yaml. Dit is het tijdstip voor het
--                 afleverbewijs. Er wordt nergens op geselecteerd of gesorteerd: het is een externe
--                 klok en die kan scheef of oud zijn.
--   geregistreerd wanneer de NMC de status vastlegde, op de eigen klok. Monotoon.
-- Die twee lopen uiteen omdat NotifyNL een mislukte callback tot 5x met 5 minuten ertussen herhaalt.
--
-- volgnummer is de @OrderColumn van Notificatie#statusGeschiedenis en bepaalt de volgorde. Zonder
-- die kolom is de collectie voor Hibernate een bag, en dan wordt elke toevoeging uitgevoerd als
-- "verwijder alle statusregels van deze notificatie en voeg de hele lijst opnieuw toe": bij vier
-- overgangen tien schrijfacties in plaats van vier, elke keer nieuwe dead tuples in een tabel die
-- alleen maar hoort te groeien. Met de kolom is een toevoeging aan het eind één INSERT.
--
-- (notificatie_id, volgnummer) is meteen de primary key. Dat geeft elke statusregel een identiteit
-- — nodig zodra er ergens naar verwezen moet worden — en het is het vangnet onder notificatie.versie:
-- twee gelijktijdige callbacks die allebei vanaf dezelfde toestand werken, willen allebei hetzelfde
-- volgnummer schrijven en de tweede loopt op deze constraint stuk in plaats van de eerste stil te
-- overschrijven. Die constraint geldt ook voor schrijvers die Hibernate omzeilen.
--
-- Een PK op (notificatie_id, tijdstip) zou dat niet doen: niets garandeert dat een tijdstip uniek is
-- per notificatie (timestamp(6) heeft een eindige resolutie), dus die zou falen op een randgeval dat
-- verder nergens iets breekt.
--
-- De volgorde is daarmee registratievolgorde, niet tijdstip. Dat is de bedoeling: tijdstip staat op
-- de klok van NotifyNL en een receipt met een scheve of oude completed_at zou anders vóór de
-- aanmaakstatus sorteren, waarna Notificatie#getAangemaakt (dat het eerste record leest) de
-- verkeerde rij teruggeeft.
--
-- ON DELETE CASCADE is verdediging in de diepte: Hibernate ruimt deze rijen bij een bulk-delete
-- (zoals de retentiejob) al zelf op; de FK dekt verwijdering buiten Hibernate om.
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

-- Backfill vóór de DROP COLUMNs hieronder: dit component draait weliswaar nog niet live in het
-- release-cluster, maar ZAD-PR-previewclusters, %dev en lokale Podman-instanties hebben persistente
-- volumes waar wél al rijen kunnen staan. Elke bestaande notificatie krijgt zo alsnog exact één
-- geschiedenisrecord (zijn huidige status op zijn aanmaaktijdstip) i.p.v. stilzwijgend zonder
-- geschiedenis te blijven zitten, zie Notificatie#eersteStatus voor wat dat anders oplevert.
-- Gebeurtenis- en registratietijd krijgen dezelfde waarde: die rijen zijn met de klok van de NMC
-- geschreven, er was geen andere bron.
-- Kanttekening: aangemaakt is het enige tijdstip dat V1 vastlegde, dus voor bestaande rijen loopt de
-- bewaartermijn vanaf de aanmaak en niet vanaf hun laatste statuswijziging. Een rij van 45 dagen oud
-- die gisteren nog DELIVERED werd, is daarmee direct opruimbaar. Aanvaardbaar omdat het alleen om
-- wegwerpomgevingen gaat (zie hierboven); in het release-cluster staat nog niets.
-- volgnummer 0: @OrderColumn is nul-gebaseerd, en elke bestaande notificatie krijgt precies één
-- record.
INSERT INTO notificatie_status (notificatie_id, volgnummer, status, tijdstip, geregistreerd)
SELECT id, 0, status, aangemaakt, aangemaakt FROM notificatie;

-- Projectie van het laatste geschiedenisrecord, bijgewerkt door Notificatie#registreerStatus:
--   laatste_status          de status zelf
--   laatste_status_tijdstip zijn gebeurtenistijd, voor het afleverbewijs
--   laatste_status_update   zijn registratietijd, waar de retentiejob op selecteert
-- De retentiejob vaart bewust op de registratietijd: één geïndexeerde bereikscan over notificatie
-- i.p.v. een scan over notificatie_status met per notificatie een MAX-subquery, én op de eigen klok.
-- Zou hij op de gebeurtenistijd varen, dan maakt een receipt met een scheve of oude completed_at een
-- notificatie meteen opruimbaar terwijl er zojuist nog iets over binnenkwam.
-- Eerst nullable toegevoegd zodat bestaande rijen gevuld kunnen worden, daarna pas NOT NULL.
ALTER TABLE notificatie ADD COLUMN laatste_status varchar(32);
ALTER TABLE notificatie ADD COLUMN laatste_status_tijdstip timestamp(6) with time zone;
ALTER TABLE notificatie ADD COLUMN laatste_status_update timestamp(6) with time zone;

UPDATE notificatie SET laatste_status = status, laatste_status_tijdstip = aangemaakt,
    laatste_status_update = aangemaakt;

ALTER TABLE notificatie ALTER COLUMN laatste_status SET NOT NULL;
ALTER TABLE notificatie ALTER COLUMN laatste_status_tijdstip SET NOT NULL;
ALTER TABLE notificatie ALTER COLUMN laatste_status_update SET NOT NULL;
ALTER TABLE notificatie ADD CONSTRAINT chk_notificatie_laatste_status CHECK (laatste_status IN (
    'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED', 'ONBEKEND'
));
-- Samengestelde index, geen losse op laatste_status_update. De retentiejob doet drie queries op
-- deze tabel en alle drie filteren op laatste_status_update; twee daarvan filteren daarnaast op
-- laatste_status (de melding van verlopen notificaties zonder definitieve status). Met alleen de
-- datumkolom moet PostgreSQL voor die twee elke rij in het verlopen bereik uit de heap halen om de
-- status te toetsen; met beide kolommen kan dat een index-only scan worden. Bij miljoenen rijen
-- scheelt dat de heap-toegang over het hele bereik.
--
-- De datumkolom staat voorop zodat de index ook de queries bedient die alleen daarop filteren (een
-- btree is bruikbaar op elk prefix van zijn kolommen). Een losse index op laatste_status_update
-- ernaast zou dus niets toevoegen en alleen schrijfkosten opleveren.
CREATE INDEX idx_notificatie_laatste_status_update_status
    ON notificatie (laatste_status_update, laatste_status);

ALTER TABLE notificatie DROP COLUMN status;
ALTER TABLE notificatie DROP COLUMN aangemaakt;
