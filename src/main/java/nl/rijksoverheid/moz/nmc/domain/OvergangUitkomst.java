package nl.rijksoverheid.moz.nmc.domain;

/**
 * Het resultaat van een aanroep van de overgangsfunctie: uitgevoerd met het geschreven event, of
 * geweigerd omdat de overgang vanuit de huidige status niet is toegestaan.
 *
 * @param van   de status vóór de aanroep, gelezen onder de rijvergrendeling
 * @param naar  de gevraagde status
 * @param event het geschreven event; null als de overgang is geweigerd
 */
public record OvergangUitkomst(NotificatieStatus van, NotificatieStatus naar, Event event) {

    public static OvergangUitkomst uitgevoerd(NotificatieStatus van, Event event) {
        return new OvergangUitkomst(van, event.getNaar(), event);
    }

    public static OvergangUitkomst geweigerd(NotificatieStatus van, NotificatieStatus naar) {
        return new OvergangUitkomst(van, naar, null);
    }

    public boolean isUitgevoerd() {
        return event != null;
    }
}
