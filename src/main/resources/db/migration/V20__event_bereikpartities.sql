-- Stap 3 van 3: de gevalideerde CHECK dekt het bereik, dus ATTACH hoeft event_0 niet te lezen. Een
-- nieuwe, lege default-partitie vangt op wat buiten de bereiken valt. Zo hoeft de onderhoudstaak bij
-- het aanmaken van een bereik nooit rijen uit de default-partitie te verplaatsen: ze begint elk nieuw
-- bereik boven de uitgedeelde transactie-ids.
DO $$
DECLARE
    tot text;
BEGIN
    SELECT substring(pg_get_constraintdef(oid) FROM 'xid < ''(\d+)''')
      INTO STRICT tot
      FROM pg_constraint
     WHERE conname = 'event_0_bereik';

    ALTER TABLE event DETACH PARTITION event_standaard;
    ALTER TABLE event_standaard RENAME TO event_0;
    EXECUTE format('ALTER TABLE event ATTACH PARTITION event_0 FOR VALUES FROM (%L) TO (%L)', '0', tot);
    ALTER TABLE event_0 DROP CONSTRAINT event_0_bereik;
    CREATE TABLE event_standaard PARTITION OF event DEFAULT;
END;
$$;
