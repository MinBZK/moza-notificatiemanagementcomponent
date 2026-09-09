package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.util.UUID;

public record NotificatieData(UUID notificatieId, StatusWaarde status) {
}
