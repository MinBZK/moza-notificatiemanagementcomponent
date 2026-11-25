package nl.rijksoverheid.moz.nmc.service.aanname;

/** De dienstverlener heeft zijn aantal aannames per dag bereikt. */
public class QuotumOverschredenException extends RuntimeException {

    public QuotumOverschredenException(int quotum) {
        super("Het quotum van " + quotum + " notificaties per dag is bereikt");
    }
}
