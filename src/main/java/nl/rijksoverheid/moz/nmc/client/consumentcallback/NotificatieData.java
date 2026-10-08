package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.annotation.JsonInclude;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

import java.util.Objects;

/**
 * Payload van het CloudEvent naar de Dienstverlener: één statusovergang. Statussen en reden gaan als
 * API-waarde over de lijn ({@code niet-bezorgbaar}).
 *
 * @param vorigeStatus de status vóór de overgang
 * @param nieuweStatus de status na de overgang
 * @param reden        de reden bij een eindstatus, anders null
 * @param versie       het volgnummer van de overgang, gelijk aan {@code sequence} in de envelop
 */
// Een leeg veld gaat niet als null over de lijn: het contract kent vorigeStatus en reden alleen als
// enumwaarde.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificatieData(NotificatieStatus vorigeStatus, NotificatieStatus nieuweStatus, Reden reden,
                              long versie) {

    // nieuweStatus is verplicht in openapi.yaml; zo valt een null hier op en niet bij de Dienstverlener.
    public NotificatieData {
        Objects.requireNonNull(nieuweStatus, "nieuweStatus is verplicht");
    }
}
