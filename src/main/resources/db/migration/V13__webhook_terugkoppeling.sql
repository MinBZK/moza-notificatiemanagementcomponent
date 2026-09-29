-- V13: de webhook uit ADR 0024. De webhook-URL staat per dienstverlener in het register in plaats van
-- per notificatie op de intake; een terugkoppeltaak per dienstverlener leest het eventlog vanaf de
-- eigen leverpositie in webhookpositie. V12 hoort bij een andere story.

-- Leeg betekent: geen webhook. Leeg aantal mislukkingen betekent: de default uit de configuratie.
ALTER TABLE dienstverlener
    ADD COLUMN webhook_url varchar(2048),
    ADD COLUMN webhook_max_mislukkingen integer CHECK (webhook_max_mislukkingen > 0);

-- De leverpositie per dienstverlener, geen bevestiging. Leeg (epoch, xid, event_id) betekent: nog
-- niets geleverd, dus vanaf het oudste beschikbare event.
CREATE TABLE webhookpositie (
    dv_id uuid PRIMARY KEY REFERENCES dienstverlener(id),
    epoch integer,
    xid xid8,
    event_id bigint,
    mislukkingen integer NOT NULL DEFAULT 0 CHECK (mislukkingen >= 0),
    gepauzeerd_tot timestamp(6) with time zone,
    bijgewerkt_op timestamp(6) with time zone NOT NULL DEFAULT now(),
    CHECK ((epoch IS NULL) = (xid IS NULL) AND (xid IS NULL) = (event_id IS NULL))
);

-- Hoogstens één terugkoppeltaak per dienstverlener, ook als hij op mislukt staat: samen met de claim
-- levert dan nooit meer dan één worker tegelijk aan dezelfde dienstverlener.
CREATE UNIQUE INDEX taak_terugkoppelen_per_dv_idx ON taak (dv_id, soort) WHERE soort = 'TERUGKOPPELEN';

-- De callback-URL per notificatie vervalt; de statusupdate gaat voortaan naar de webhook van de
-- dienstverlener.
ALTER TABLE notificatie DROP COLUMN callback_url;
