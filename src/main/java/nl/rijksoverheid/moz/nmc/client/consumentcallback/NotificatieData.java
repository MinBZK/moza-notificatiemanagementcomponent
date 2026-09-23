package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;

import java.util.Objects;
import java.util.UUID;

/**
 * Payload van het CloudEvent naar de Dienstverlener; status wordt via StatusWaarde#toApiValue als
 * API-waarde geserialiseerd.
 * <p>
 * TODO (buiten scope): zonder volgnummer kan de Dienstverlener een ingehaalde update niet herkennen.
 * Het volgnummer uit {@code notificatie_status} meesturen lost dat op, maar verandert het contract.
 */
public record NotificatieData(UUID notificatieId, StatusWaarde status) {

    // Beide velden zijn verplicht in openapi.yaml; zo valt een null hier op en niet bij de Dienstverlener.
    public NotificatieData {
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(status, "status is verplicht");
    }
}
