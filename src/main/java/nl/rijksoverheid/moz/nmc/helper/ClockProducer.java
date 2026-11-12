package nl.rijksoverheid.moz.nmc.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/** De klok als bean: één bron van tijd voor de hele applicatie, die in tests vast te zetten is. */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}
