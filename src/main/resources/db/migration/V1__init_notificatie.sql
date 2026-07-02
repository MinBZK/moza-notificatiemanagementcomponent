-- V1: Initieel gegevensmodel voor de NotificatieManagementComponent.
-- Komt overeen met de Notificatie-entiteit (nl.rijksoverheid.moz.nmc.domain.Notificatie).

CREATE TABLE notificatie (
    id uuid PRIMARY KEY,
    external_reference uuid UNIQUE,
    callback_url varchar(2048),
    status varchar(17) NOT NULL CHECK (status IN (
        'SENDING', 'DELIVERED', 'PERMANENT_FAILURE', 'TEMPORARY_FAILURE', 'TECHNICAL_FAILURE', 'CREATED'
    )),
    aangemaakt timestamp(6) with time zone NOT NULL
);
