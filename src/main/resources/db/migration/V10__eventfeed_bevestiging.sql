-- V10: de eventfeed uit ADR 0024. bevestiging is de cursor die de dienstverlener zelf terugschrijft:
-- één rij per dienstverlener, alleen vooruit te zetten. De cursor is (cluster-epoch, transactie-id,
-- event-id); het epoch verhoogt beheer bij herstel uit back-up, zodat oude cursors vervallen.

CREATE TABLE bevestiging (
    dv_id uuid PRIMARY KEY REFERENCES dienstverlener(id),
    epoch integer NOT NULL,
    xid xid8 NOT NULL,
    event_id bigint NOT NULL,
    bevestigd_op timestamp(6) with time zone NOT NULL
);

-- De feed leest per dienstverlener op (xid, id) vanaf de cursor. Geen CONCURRENTLY: PostgreSQL
-- ondersteunt dat niet op een gepartitioneerde tabel, en het log is nog klein.
CREATE INDEX event_feed_idx ON event (dv_id, xid, id);
