package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;

import java.util.UUID;

public record NotificatieData(UUID notificatieId, NotificatieStatus status) {
}
