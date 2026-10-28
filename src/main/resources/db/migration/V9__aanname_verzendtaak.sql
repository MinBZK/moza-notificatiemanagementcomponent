-- V9: de aanname wordt een 202 met een verzendtaak. Het aannametijdstip krijgt een eigen kolom voor
-- het quotum per dienstverlener per dag, het register krijgt dat quotum, de verzendgegevens staan op
-- de notificatie zodat elke verzendtaak kan versturen, en de controletaak per systeem krijgt haar
-- eerste rij; daarna plant ze zichzelf steeds opnieuw.

ALTER TABLE notificatie ADD COLUMN aangenomen_op timestamp(6) with time zone;

-- De updates zetten deferred triggercontroles klaar (zelfde versie en status, dus die slagen), en
-- zolang die openstaan weigert PostgreSQL DDL op notificatie.
SET CONSTRAINTS notificatie_overgang IMMEDIATE;

UPDATE notificatie n
   SET aangenomen_op = e.geregistreerd
  FROM event e
 WHERE e.notificatie_id = n.id
   AND e.volgnummer = 0;

UPDATE notificatie SET aangenomen_op = laatste_status_update WHERE aangenomen_op IS NULL;

-- Default voor schrijvers buiten Hibernate om; de entity zet het tijdstip zelf.
ALTER TABLE notificatie
    ALTER COLUMN aangenomen_op SET NOT NULL,
    ALTER COLUMN aangenomen_op SET DEFAULT now();

CREATE INDEX notificatie_dv_aangenomen_idx ON notificatie (dv_id, aangenomen_op);

-- De controletaak zoekt open notificaties zonder taak, oudste eerst. Rijen met een eindstatus blijven
-- jaren staan; de index bevat alleen de open rijen, zodat die zoektocht niet elke ronde de hele tabel
-- leest.
CREATE INDEX notificatie_open_aangenomen_idx ON notificatie (aangenomen_op)
    WHERE status IN ('AANGENOMEN', 'IN_VERZENDING', 'VERZONDEN', 'BEZORGD');

-- Template, regie, dienstverlener en dienst voor de Profielservice-lookup.
ALTER TABLE notificatie
    ADD COLUMN template_id varchar(64),
    ADD COLUMN regie varchar(16) CHECK (regie IN ('CENTRAAL', 'DECENTRAAL')),
    ADD COLUMN dienstverlener_naam varchar(255),
    ADD COLUMN dienst varchar(255);

-- Leeg betekent onbeperkt.
ALTER TABLE dienstverlener ADD COLUMN quotum_per_dag integer CHECK (quotum_per_dag > 0);

INSERT INTO taak (soort, due, status) VALUES ('CONTROLE', now(), 'OPEN');
