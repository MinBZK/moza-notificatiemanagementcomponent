-- V7: ontvanger en personalisation versleuteld op de notificatierij, met een sleutel per
-- notificatie die gewrapt is door de KEK van versie kek_versie.
-- Nullable: bestaande rijen hebben deze gegevens niet, en het wissen van de sleutel zet
-- sleutel_gewrapt op null.

ALTER TABLE notificatie
    ADD COLUMN ontvanger_versleuteld bytea,
    ADD COLUMN personalisation_versleuteld bytea,
    ADD COLUMN sleutel_gewrapt bytea,
    ADD COLUMN kek_versie integer;

-- De onderhoudstaak zoekt elk uur sleutels onder een oudere KEK-versie; zonder index loopt die de hele
-- tabel door. Partieel, zodat alleen rijen met een sleutel erin staan.
CREATE INDEX notificatie_kek_versie_idx ON notificatie (kek_versie, id) WHERE sleutel_gewrapt IS NOT NULL;
