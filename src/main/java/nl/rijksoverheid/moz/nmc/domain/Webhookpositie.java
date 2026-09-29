package nl.rijksoverheid.moz.nmc.domain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Tot waar de terugkoppeltaak de events van een dienstverlener aan zijn webhook heeft geleverd, met
 * de mislukkingen sinds de laatste geslaagde levering. Geen bevestiging: die schrijft de
 * Dienstverlener zelf met de feedcursor.
 *
 * @param positie       het laatst geleverde event; null als er nog niets is geleverd
 * @param mislukkingen  het aantal mislukte leveringen op rij
 * @param gepauzeerdTot tot wanneer de webhook gepauzeerd is; null als hij niet gepauzeerd is
 */
public record Webhookpositie(UUID dvId, Cursor positie, int mislukkingen, OffsetDateTime gepauzeerdTot) {

    /** Een dienstverlener waaraan nog nooit geleverd is. */
    public static Webhookpositie nieuw(UUID dvId) {
        return new Webhookpositie(dvId, null, 0, null);
    }

    public Optional<Cursor> laatstGeleverd() {
        return Optional.ofNullable(positie);
    }
}
