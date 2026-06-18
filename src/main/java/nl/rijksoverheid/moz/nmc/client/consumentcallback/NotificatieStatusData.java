package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.common.NotificatieStatus;

import java.util.UUID;

public record NotificatieStatusData(UUID notificatieId, NotificatieStatus status) {
}
