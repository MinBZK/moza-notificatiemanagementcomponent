package nl.rijksoverheid.moz.nmc.helper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/** De klok als bean, zodat tijdvakken in tests op een vast moment gezet kunnen worden. */
@ApplicationScoped
public class KlokProducer {

    @Produces
    @ApplicationScoped
    public Clock klok() {
        return Clock.systemUTC();
    }
}
