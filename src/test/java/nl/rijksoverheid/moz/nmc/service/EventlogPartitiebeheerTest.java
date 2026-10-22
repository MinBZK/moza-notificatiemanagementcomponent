package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

// De configuratiecontroles; het partitieplan staat in EventPartitieTest, de DDL in OnderhoudTaakHandlerTest.
class EventlogPartitiebeheerTest {

    @Test
    void constructor_ongeldigeOmvangOfMarge_weigert() {
        Opruimtermijnen termijnen = new Opruimtermijnen(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3));

        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 0, 0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 10, -1, Duration.ofSeconds(1), 1));
    }

    // Nul is voor PostgreSQL geen lock-timeout: elke schrijver zou dan tot de statement-timeout wachten.
    @Test
    void constructor_lockTimeoutOnderEenMilliseconde_weigert() {
        Opruimtermijnen termijnen = new Opruimtermijnen(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3));

        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 10, 0, Duration.ZERO, 1));
        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 10, 0, Duration.ofNanos(500_000), 1));
    }
}
