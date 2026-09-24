-- V6: ontvanger en personalisation versleuteld op de notificatierij, met een sleutel per
-- notificatie die gewrapt is door de KEK van versie kek_versie.
-- Nullable: bestaande rijen hebben deze gegevens niet, en het wissen van de sleutel zet
-- sleutel_gewrapt op null.

ALTER TABLE notificatie
    ADD COLUMN ontvanger_versleuteld bytea,
    ADD COLUMN personalisation_versleuteld bytea,
    ADD COLUMN sleutel_gewrapt bytea,
    ADD COLUMN kek_versie integer;
