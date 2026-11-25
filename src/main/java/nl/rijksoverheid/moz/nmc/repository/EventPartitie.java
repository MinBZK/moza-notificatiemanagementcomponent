package nl.rijksoverheid.moz.nmc.repository;

import java.util.Optional;
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

    /**
     * De partitie die na {@code laatste} moet komen. Is de laatste nog niet voor 80% gevuld, dan geen.
     * Ligt de volgende transactie-id er al boven, dan begint de nieuwe {@code marge} daarboven: de
     * default-partitie kan rijen tussen de laatste en die transactie-id hebben, en een transactie die
     * tijdens het aanmaken een event schrijft, mag niet in het nieuwe bereik vallen.
     */
    public static Optional<EventPartitie> volgende(Optional<EventPartitie> laatste, long volgendeTransactieId, long omvang, long marge) {
        if (laatste.isEmpty() || volgendeTransactieId >= laatste.get().tot()) {
            return Optional.of(vanaf(volgendeTransactieId + marge, volgendeTransactieId + marge + omvang));
        }

        EventPartitie lopend = laatste.get();

        if ((volgendeTransactieId - lopend.van()) * 5 >= (lopend.tot() - lopend.van()) * 4) {
            return Optional.of(vanaf(lopend.tot(), lopend.tot() + omvang));
        }

        return Optional.empty();
    }
}
