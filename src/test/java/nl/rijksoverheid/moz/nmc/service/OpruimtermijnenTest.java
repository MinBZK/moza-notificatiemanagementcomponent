package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpruimtermijnenTest {

    private static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    private final Opruimtermijnen termijnen = TestConfig.opruimtermijnen(Duration.ofDays(7), Duration.ofDays(365), Duration.ofDays(30));

    @Test
    void wissenOp_ligtDeWistermijnNaDeEindstatus() {
        assertEquals(MOMENT.plusDays(7), termijnen.wissenOp(MOMENT));
    }

    @Test
    void bewaargrens_ligtDeBewaartermijnVoorHetMoment() {
        assertEquals(MOMENT.minusDays(365), termijnen.bewaargrens(MOMENT));
    }

    @Test
    void maxCursorleeftijd_komtUitDeConfiguratie() {
        assertEquals(Duration.ofDays(30), termijnen.maxCursorleeftijd());
    }

    // Gelijke termijnen mogen: de rij en de sleutel verdwijnen dan in dezelfde ronde.
    @Test
    void constructor_bewaartermijnGelijkAanWistermijn_isToegestaan() {
        Opruimtermijnen termijnen = TestConfig.opruimtermijnen(Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(1));

        assertEquals(MOMENT.minusDays(7), termijnen.bewaargrens(MOMENT));
    }
}
