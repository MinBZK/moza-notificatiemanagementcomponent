-- V2: hernoemt kolommen naar snake_case voor leesbaarheid en past kolombreedtes aan:
-- callback_url was te kort voor een realistische URL, status onnodig ruim (langste waarde is
-- 17 tekens).

ALTER TABLE notificatie RENAME COLUMN notifynlnotificatieid TO notifynl_notificatie_id;
ALTER TABLE notificatie RENAME COLUMN callbackurl TO callback_url;

ALTER TABLE notificatie ALTER COLUMN callback_url TYPE varchar(2048);
ALTER TABLE notificatie ALTER COLUMN status TYPE varchar(32);
