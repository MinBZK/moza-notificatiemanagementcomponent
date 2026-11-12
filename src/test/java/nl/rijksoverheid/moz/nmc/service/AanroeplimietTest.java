package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.testhelper.VerstelbareClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AanroeplimietTest {

    private static final UUID DV_A = UUID.randomUUID();
    private static final UUID DV_B = UUID.randomUUID();

    private final VerstelbareClock clock = new VerstelbareClock(Instant.parse("2030-01-01T10:00:05Z"));

    @Test
    void registreer_binnenDeMinuut_teltTotDeLimietPerDienstverlener() {
        Aanroeplimiet limiet = new Aanroeplimiet(clock, 2);

        assertTrue(limiet.registreer(DV_A));
        assertTrue(limiet.registreer(DV_A));
        assertFalse(limiet.registreer(DV_A));
        assertTrue(limiet.registreer(DV_B), "de limiet geldt per dienstverlener");
    }

    @Test
    void registreer_inEenNieuweMinuut_begintOpnieuw() {
        Aanroeplimiet limiet = new Aanroeplimiet(clock, 1);
        limiet.registreer(DV_A);
        assertFalse(limiet.registreer(DV_A));

        clock.zet(Instant.parse("2030-01-01T10:01:00Z"));

        assertTrue(limiet.registreer(DV_A));
    }

    @Test
    void secondenTotVolgendVenster_teltAfNaarDeVolgendeMinuut() {
        Aanroeplimiet limiet = new Aanroeplimiet(clock, 1);

        assertEquals(55, limiet.secondenTotVolgendVenster());

        clock.zet(Instant.parse("2030-01-01T10:00:59Z"));

        assertEquals(1, limiet.secondenTotVolgendVenster());

        clock.zet(Instant.parse("2030-01-01T10:01:00Z"));

        assertEquals(60, limiet.secondenTotVolgendVenster());
    }

    @Test
    void constructor_nietPositieveLimiet_weigert() {
        assertThrows(IllegalStateException.class, () -> new Aanroeplimiet(clock, 0));
    }
}
