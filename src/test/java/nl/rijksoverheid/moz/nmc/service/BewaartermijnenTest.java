package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BewaartermijnenTest {

    private static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    @Test
    void wissenOp_ligtDeWistermijnNaDeTerminaleStatus() {
        Bewaartermijnen termijnen = new Bewaartermijnen(Duration.ofDays(7), Duration.ofDays(365), Duration.ofDays(30));

        assertEquals(MOMENT.plusDays(7), termijnen.wissenOp(MOMENT));
        assertEquals(MOMENT.minusDays(365), termijnen.bewarenVanaf(MOMENT));
        assertEquals(Duration.ofDays(30), termijnen.maxCursorleeftijd());
    }

    // Gelijke termijnen mogen: de rij en de sleutel verdwijnen dan in dezelfde ronde.
    @Test
    void constructor_bewaartermijnGelijkAanWistermijn_isToegestaan() {
        Bewaartermijnen termijnen = new Bewaartermijnen(Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(1));

        assertEquals(MOMENT.minusDays(7), termijnen.bewarenVanaf(MOMENT));
    }

    @Test
    void constructor_bewaartermijnKorterDanWistermijn_weigert() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new Bewaartermijnen(Duration.ofDays(8), Duration.ofDays(7), Duration.ofDays(30)));

        assertTrue(fout.getMessage().contains("nmc.afleverbewijs.bewaartermijn"), fout.getMessage());
    }

    @Test
    void constructor_nulOfNegatieveTermijn_weigert() {
        assertThrows(IllegalStateException.class,
                () -> new Bewaartermijnen(Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30)));
        assertThrows(IllegalStateException.class,
                () -> new Bewaartermijnen(Duration.ofDays(1), Duration.ofDays(-7), Duration.ofDays(30)));
        assertThrows(IllegalStateException.class,
                () -> new Bewaartermijnen(Duration.ofDays(1), Duration.ofDays(7), Duration.ZERO));
    }
}
