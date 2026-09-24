package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;

import java.util.Objects;

/**
 * Payload van het CloudEvent naar de Dienstverlener: één statusovergang. Statussen en reden gaan als
 * API-waarde over de lijn ({@code niet-bezorgbaar}).
 *
 * @param van    de status vóór de overgang
 * @param naar   de status na de overgang
 * @param reden  de reden bij een terminale status, anders null
 * @param versie het volgnummer van de overgang, gelijk aan {@code sequence} in de envelop
 */
public record NotificatieData(NotificatieStatus van, NotificatieStatus naar, Reden reden, long versie) {

    // naar is verplicht in openapi.yaml; zo valt een null hier op en niet bij de Dienstverlener.
    public NotificatieData {
        Objects.requireNonNull(naar, "naar is verplicht");
    }
}
