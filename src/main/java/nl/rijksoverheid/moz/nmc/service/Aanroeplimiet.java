package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    private final Clock klok;
    private final int maxPerMinuut;
    private final ConcurrentMap<UUID, Venster> vensters = new ConcurrentHashMap<>();

    public Aanroeplimiet(Clock klok, @ConfigProperty(name = "nmc.feed.max-aanroepen-per-minuut") int maxPerMinuut) {
        if (maxPerMinuut < 1) {
            throw new IllegalStateException("nmc.feed.max-aanroepen-per-minuut moet 1 of hoger zijn, is " + maxPerMinuut);
        }

        this.klok = klok;
        this.maxPerMinuut = maxPerMinuut;
    }

    /** Telt een aanroep en geeft terug of hij nog binnen de limiet van de lopende minuut valt. */
    public boolean registreer(UUID dvId) {
        long minuut = klok.instant().getEpochSecond() / 60;
        Venster venster = vensters.compute(dvId, (id, huidig) -> huidig == null || huidig.minuut() != minuut
                ? new Venster(minuut, 1)
                : new Venster(minuut, huidig.aantal() + 1));

        return venster.aantal() <= maxPerMinuut;
    }

    private record Venster(long minuut, int aantal) {
    }
}
