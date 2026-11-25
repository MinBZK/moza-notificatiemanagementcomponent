package nl.rijksoverheid.moz.nmc.service.feed;

import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.config.FeedConfig;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Telt de feedaanroepen per dienstverlener per minuut, in het geheugen van deze pod. Met meerdere
 * pods geldt de limiet dus per pod.
 */
@ApplicationScoped
public class Aanroeplimiet {

    private final Clock clock;
    private final int maxPerMinuut;
    private final ConcurrentMap<UUID, Venster> vensters = new ConcurrentHashMap<>();

    public Aanroeplimiet(Clock clock, FeedConfig config) {
        this.clock = clock;
        this.maxPerMinuut = config.maxAanroepenPerMinuut();
    }

    /** Telt een aanroep en geeft terug of hij nog binnen de limiet van de lopende minuut valt. */
    public boolean registreer(UUID dvId) {
        long minuut = clock.instant().getEpochSecond() / 60;
        // De teller staat in het geheugen van deze pod: met N pods is de limiet in de praktijk N keer
        // zo hoog.
        Venster venster = vensters.compute(dvId, (id, huidig) -> huidig == null || huidig.minuut() != minuut
                ? new Venster(minuut, 1)
                : new Venster(minuut, huidig.aantal() + 1));

        return venster.aantal() <= maxPerMinuut;
    }

    /** Hoeveel seconden het lopende venster nog duurt; na afloop telt de limiet opnieuw. */
    public int secondenTotVolgendVenster() {
        return (int) (60 - clock.instant().getEpochSecond() % 60);
    }

    private record Venster(long minuut, int aantal) {
    }
}
