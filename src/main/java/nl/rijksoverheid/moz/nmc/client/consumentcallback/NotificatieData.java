package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import java.util.Objects;
import java.util.UUID;

/**
 * De payload van het CloudEvent naar de Dienstverlener. status is een String en geen StatusWaarde:
 * dit is een extern contract, dus de waarde staat hier los van de interne enum.
 * <p>
 * TODO (buiten scope): er zit geen volgordeinformatie in, terwijl statusupdates elkaar kunnen
 * inhalen — ConsumentCallbackAdapter herprobeert synchroon, dus een latere receipt kan als eerste
 * aankomen. De Dienstverlener kan een verouderde update niet herkennen: {@code
 * NotificatieStatusEvent#time} is het verzendmoment en er is geen volgnummer.
 * <p>
 * Op te lossen door het volgnummer uit {@code notificatie_status} mee te sturen; dat is monotoon per
 * notificatie en niet klokafhankelijk. Dat verandert het CloudEvent-contract, dus ook het schema in
 * {@code META-INF/openapi.yaml}.
 */
public record NotificatieData(UUID notificatieId, String status) {

    // Beide velden zijn verplicht in NotificatieStatusData in openapi.yaml; zonder deze controle gaat
    // een null als ongeldig CloudEvent naar de Dienstverlener in plaats van hier op te vallen.
    public NotificatieData {
        Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        Objects.requireNonNull(status, "status is verplicht");
    }
}
