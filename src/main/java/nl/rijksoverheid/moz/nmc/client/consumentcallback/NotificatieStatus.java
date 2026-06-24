package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;

import java.util.UUID;

public record NotificatieStatus(UUID notificatieId, NotificatieStatusEnum status) {
}
