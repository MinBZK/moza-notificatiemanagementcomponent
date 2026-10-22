package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Vertaalt de afleverstatus uit een NotifyNL-receipt naar de uitkomst van een poging.
 */
public final class NotifyNLAfleverstatus {

    private NotifyNLAfleverstatus() {
    }

    /**
     * De tussenstatussen van NotifyNL leveren geen uitkomst op. Een onbekende status wordt op ERROR
     * gelogd en levert ook niets op, zodat hij niet als een bekende uitkomst landt.
     */
    public static Optional<PogingStatus> naarUitkomst(String status, UUID notifyId) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "delivered" -> Optional.of(PogingStatus.BEZORGD);
            case "permanent-failure" -> Optional.of(PogingStatus.PERMANENT_MISLUKT);
            case "temporary-failure" -> Optional.of(PogingStatus.TIJDELIJK_MISLUKT);
            case "technical-failure" -> Optional.of(PogingStatus.TECHNISCH_MISLUKT);
            case "created", "sending", "pending" -> {
                Log.debugf("Tussenstatus %s voor NotifyNL-referentie %s genegeerd", status, notifyId);
                yield Optional.empty();
            }
            default -> {
                Log.errorf("Onbekende NotifyNL-status '%s' ontvangen voor NotifyNL-referentie %s; niets vastgelegd. "
                        + "Controleer of NotifyNL nieuwe statussen is gaan sturen", status, notifyId);
                yield Optional.empty();
            }
        };
    }
}
