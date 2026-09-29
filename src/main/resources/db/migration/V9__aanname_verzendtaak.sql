-- V9: de aanname wordt een 202 met een verzendtaak. Het aannametijdstip krijgt een eigen kolom voor
-- het quotum per dienstverlener per dag, het register krijgt dat quotum, en de controletaak per
-- systeem krijgt haar eerste rij; daarna plant ze zichzelf steeds opnieuw.

ALTER TABLE notificatie ADD COLUMN aangenomen_op timestamp(6) with time zone;

UPDATE notificatie n
   SET aangenomen_op = e.tijdstip
  FROM event e
 WHERE e.notificatie_id = n.id
   AND e.volgnummer = 0;

UPDATE notificatie SET aangenomen_op = laatste_status_update WHERE aangenomen_op IS NULL;

-- Default voor schrijvers buiten Hibernate om; de entity zet het tijdstip zelf.
ALTER TABLE notificatie
    ALTER COLUMN aangenomen_op SET NOT NULL,
    ALTER COLUMN aangenomen_op SET DEFAULT now();

CREATE INDEX notificatie_dv_aangenomen_idx ON notificatie (dv_id, aangenomen_op);

-- Leeg betekent onbeperkt.
ALTER TABLE dienstverlener ADD COLUMN quotum_per_dag integer CHECK (quotum_per_dag > 0);

INSERT INTO taak (soort, due, status) VALUES ('CONTROLE', now(), 'OPEN');
