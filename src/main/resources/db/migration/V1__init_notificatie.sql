-- V1: Initieel gegevensmodel voor de NotificatieManagementComponent.
-- Komt overeen met de Notificatie-entiteit (nl.rijksoverheid.moz.nmc.domain.Notificatie).

CREATE TABLE notificatie (
    id uuid PRIMARY KEY,
    notifynlnotificatieid uuid UNIQUE,
    callbackurl varchar(255),
    status varchar(255) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE',
        'PENDING', 'SENT', 'ACCEPTED', 'RECEIVED', 'CANCELLED', 'CREATED'
    )),
    aangemaakt timestamp(6) with time zone NOT NULL
);
