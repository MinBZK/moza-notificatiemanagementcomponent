-- Stap 1 van 3: de default-partitie wordt de eerste bereikpartitie, event_0, tot een grens boven de
-- uitgedeelde transactie-ids. De marge vangt de events op die binnenkomen voordat V20 de partitie
-- omzet. NOT VALID, zodat het toevoegen de tabel niet leest en het slot kort duurt.
DO $$
BEGIN
    EXECUTE format('ALTER TABLE event_standaard ADD CONSTRAINT event_0_bereik '
                   'CHECK (xid >= %L::xid8 AND xid < %L::xid8) NOT VALID',
                   '0', (pg_snapshot_xmax(pg_current_snapshot())::text::bigint + ${event_marge})::text);
END;
$$;
