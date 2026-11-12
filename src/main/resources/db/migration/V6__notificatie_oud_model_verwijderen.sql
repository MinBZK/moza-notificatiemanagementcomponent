-- V6: de oude statusgeschiedenis en de NotifyNL-referentie op de notificatie vervallen. Sinds V5
-- staan de geschiedenis in event en de NotifyNL-id op poging, en leest niets deze nog.
-- laatste_status en laatste_status_update blijven: de retentiejob selecteert op laatste_status_update.

DROP TABLE notificatie_status;

ALTER TABLE notificatie DROP COLUMN external_reference;
