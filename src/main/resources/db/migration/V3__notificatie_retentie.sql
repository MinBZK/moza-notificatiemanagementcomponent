-- V3: index voor de retentiejob.
-- De retentiejob claimt de oudste verlopen rijen, filterend en ordenend op laatste_status_update: de
-- registratietijd op de eigen klok, zodat een receipt met een oude completed_at een notificatie niet
-- meteen opruimbaar maakt. Alleen die kolom hoort in de index; een tweede kolom levert geen
-- index-only scan op, want de claim leest id en external_reference toch uit de heap.
CREATE INDEX idx_notificatie_laatste_status_update ON notificatie (laatste_status_update);
