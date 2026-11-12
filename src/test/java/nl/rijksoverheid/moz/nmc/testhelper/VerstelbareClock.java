package nl.rijksoverheid.moz.nmc.testhelper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Een klok die stilstaat tot de test hem verzet. */
public final class VerstelbareClock extends Clock {

    private Instant nu;

    public VerstelbareClock(Instant nu) {
        this.nu = nu;
    }

    /** Staat stil op het huidige tijdstip, op microseconden zoals de database het bewaart. */
    public static VerstelbareClock opNu() {
        return new VerstelbareClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public void zet(Instant nu) {
        this.nu = nu;
    }

    public OffsetDateTime nu() {
        return nu.atOffset(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return nu;
    }
}
