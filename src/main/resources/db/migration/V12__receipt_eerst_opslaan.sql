-- V12: receipts van NotifyNL worden eerst als taak opgeslagen en daarna verwerkt. Meerdere receipts
-- voor één notificatie mogen tegelijk open staan, dus receipttaken vallen buiten de unieke index op
-- (notificatie, soort); een receipt is uniek op poging en tijdstip uit de receipt, zodat een herhaling
-- van NotifyNL geen tweede taak oplevert. V11 hoort bij de aannamestory.

DROP INDEX taak_open_per_notificatie_idx;

CREATE UNIQUE INDEX taak_open_per_notificatie_idx ON taak (notificatie_id, soort)
    WHERE status = 'OPEN' AND notificatie_id IS NOT NULL AND soort <> 'RECEIPT_VERWERKEN';

-- Op de partitie, niet op de gepartitioneerde tabel: een unieke index daarop moet de
-- partitiesleutel als kolom bevatten en kan geen expressie op de payload zijn.
CREATE UNIQUE INDEX taak_receipt_uniek_idx ON taak_receipt_verwerken
    ((payload ->> 'pogingId'), (payload ->> 'status'), (payload ->> 'tijdstip'));
