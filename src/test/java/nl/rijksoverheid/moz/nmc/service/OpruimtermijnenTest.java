package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpruimtermijnenTest {

    private static final OffsetDateTime MOMENT = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    private final Opruimtermijnen termijnen = new Opruimtermijnen(Duration.ofDays(7), Duration.ofDays(365), Duration.ofDays(30));

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
        Opruimtermijnen termijnen = new Opruimtermijnen(Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(1));

        assertEquals(MOMENT.minusDays(7), termijnen.bewaargrens(MOMENT));
    }

    @Test
    void constructor_bewaartermijnKorterDanWistermijn_weigert() {
        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> new Opruimtermijnen(Duration.ofDays(8), Duration.ofDays(7), Duration.ofDays(30)));

        assertTrue(fout.getMessage().contains("nmc.afleverbewijs.bewaartermijn"), fout.getMessage());
    }

    @Test
    void constructor_nulOfNegatieveTermijn_weigert() {
        assertThrows(IllegalStateException.class,
                () -> new Opruimtermijnen(Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30)));
        assertThrows(IllegalStateException.class,
                () -> new Opruimtermijnen(Duration.ofDays(1), Duration.ofDays(-7), Duration.ofDays(30)));
        assertThrows(IllegalStateException.class,
                () -> new Opruimtermijnen(Duration.ofDays(1), Duration.ofDays(7), Duration.ZERO));
    }
}
