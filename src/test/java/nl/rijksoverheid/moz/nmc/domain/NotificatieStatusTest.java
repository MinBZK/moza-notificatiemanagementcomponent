package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bewaakt dat NotificatieStatus zijn tijdstippen normaliseert naar wat de database kan bewaren.
 * Zonder die normalisatie verschilt het gedrag per dialect: PostgreSQL bewaart in een timestamptz
 * alleen het moment en levert UTC terug, H2 (de teststack) bewaart de offset wel. Een test die op
 * H2 slaagt zou dan op PostgreSQL kunnen falen — precies het soort verschil dat pas in productie
 * opvalt.
 */
class NotificatieStatusTest {

    // NotifyNL mag in completed_at een andere offset dan Z meesturen. Die moet hier meteen UTC
    // worden, want PostgreSQL geeft hem toch als UTC terug en OffsetDateTime#equals eist dezelfde
    // offset, niet alleen hetzelfde moment.
    @Test
    void constructor_metEenAndereOffsetDanUtc_normaliseertNaarUtc() {
        OffsetDateTime metOffset = OffsetDateTime.parse("2026-03-01T14:00:00+02:00");

        NotificatieStatus status = new NotificatieStatus(StatusWaarde.DELIVERED, metOffset, metOffset);

        assertEquals(ZoneOffset.UTC, status.tijdstip().getOffset());
        assertEquals(ZoneOffset.UTC, status.geregistreerd().getOffset());
        assertEquals(OffsetDateTime.parse("2026-03-01T12:00:00Z"), status.tijdstip());
    }

    // De kolommen zijn timestamp(6); zonder afkappen verschilt een net aangemaakt record van
    // datzelfde record na herladen.
    @Test
    void constructor_metNanosecondeprecisie_kaptAfOpMicroseconden() {
        OffsetDateTime metNanos = OffsetDateTime.parse("2026-03-01T12:00:00.123456789Z");

        NotificatieStatus status = new NotificatieStatus(StatusWaarde.DELIVERED, metNanos, metNanos);

        assertEquals(OffsetDateTime.parse("2026-03-01T12:00:00.123456Z"), status.tijdstip());
        assertEquals(OffsetDateTime.parse("2026-03-01T12:00:00.123456Z"), status.geregistreerd());
    }
}
