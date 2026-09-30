-- V21: het verwijderen van een notificatie verwijdert haar taken via ON DELETE CASCADE. Die controle
-- zoekt op notificatie_id zonder status of soort en kan de partiële indexen niet gebruiken; zonder
-- deze index doorzoekt elke verwijderde notificatie alle partities van taak.
CREATE INDEX taak_notificatie_idx ON taak (notificatie_id);
