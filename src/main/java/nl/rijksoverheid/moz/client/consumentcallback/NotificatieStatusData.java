package nl.rijksoverheid.moz.client.consumentcallback;

import java.util.UUID;

public record NotificatieStatusData(UUID notificatieId, String status) {
}
