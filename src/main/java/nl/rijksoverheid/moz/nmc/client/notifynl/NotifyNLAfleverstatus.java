package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkus.logging.Log;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * De afleverstatus van een verzending zoals NotifyNL hem geeft, in een receipt of bij een navraag, en
 * de vertaling daarvan naar de uitkomst van een poging.
 *
 * @param status   de NotifyNL-status, zoals {@code delivered} of {@code sending}
 * @param tijdstip {@code completed_at}, met {@code sent_at} en {@code created_at} als terugval; null
 *                 als geen van drieën bruikbaar is
 */
public record NotifyNLAfleverstatus(String status, OffsetDateTime tijdstip) {

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

    /** Of NotifyNL deze status als tussenstatus gebruikt: de verzending loopt nog. */
    public static boolean isTussenstatus(String status) {
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "created", "sending", "pending" -> true;
            default -> false;
        };
    }

    /** De uitkomst van deze status voor de poging; leeg voor een tussenstatus of een onbekende status. */
    public Optional<PogingStatus> uitkomst(UUID notifyId) {
        return naarUitkomst(status, notifyId);
    }
}
