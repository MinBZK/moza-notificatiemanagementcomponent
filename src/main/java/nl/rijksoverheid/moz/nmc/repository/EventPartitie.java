package nl.rijksoverheid.moz.nmc.repository;

import java.util.regex.Pattern;

/**
 * Een bereikpartitie van het eventlog: de events met een transactie-id vanaf {@code van} tot (niet
 * tot en met) {@code tot}. De naam is {@code event_<van>}.
 */
public record EventPartitie(String naam, long van, long tot) {

    private static final Pattern NAAM = Pattern.compile("event_\\d+");

    public EventPartitie {
        // De naam komt in DDL terecht; alleen de vorm die de onderhoudstaak zelf kiest mag erdoor.
        if (!NAAM.matcher(naam).matches()) {
            throw new IllegalArgumentException("Onverwachte naam voor een eventpartitie: " + naam);
        }

        if (van < 0 || tot <= van) {
            throw new IllegalArgumentException("Ongeldig bereik voor eventpartitie " + naam + ": " + van + " tot " + tot);
        }
    }

    public static EventPartitie vanaf(long van, long tot) {
        return new EventPartitie("event_" + van, van, tot);
    }
}
