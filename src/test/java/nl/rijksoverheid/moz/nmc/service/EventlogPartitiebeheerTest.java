package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Welke partitie EventlogPartitiebeheer als volgende aanmaakt; de DDL zelf staat in OnderhoudTaakHandlerTest.
class EventlogPartitiebeheerTest {

    private static final EventPartitie LOPEND = EventPartitie.vanaf(1000, 2000);

    @Test
    void volgende_zonderBereikpartitie_begintBijDeVolgendeXid() {
        assertEquals(Optional.of(EventPartitie.vanaf(1234, 1334)), EventlogPartitiebeheer.volgende(Optional.empty(), 1234, 100, 0));
    }

    @Test
    void volgende_onder80Procent_maaktGeenNieuwe() {
        assertEquals(Optional.empty(), EventlogPartitiebeheer.volgende(Optional.of(LOPEND), 1799, 1000, 10));
    }

    @Test
    void volgende_op80Procent_sluitAanOpDeLopende() {
        assertEquals(Optional.of(EventPartitie.vanaf(2000, 2500)), EventlogPartitiebeheer.volgende(Optional.of(LOPEND), 1800, 500, 10));
    }

    @Test
    void volgende_lopendeVol_begintBovenDeUitgedeeldeXids() {
        assertEquals(Optional.of(EventPartitie.vanaf(2000, 3000)), EventlogPartitiebeheer.volgende(Optional.of(LOPEND), 2000, 1000, 0));
        assertEquals(Optional.of(EventPartitie.vanaf(2345, 3345)), EventlogPartitiebeheer.volgende(Optional.of(LOPEND), 2345, 1000, 0));
    }

    // Een event dat tijdens het aanmaken binnenkomt, mag niet in het nieuwe bereik vallen.
    @Test
    void volgende_achterstand_begintEenMargeBovenDeVolgendeXid() {
        assertEquals(Optional.of(EventPartitie.vanaf(2400, 3400)), EventlogPartitiebeheer.volgende(Optional.of(LOPEND), 2345, 1000, 55));
        assertEquals(Optional.of(EventPartitie.vanaf(1250, 1350)), EventlogPartitiebeheer.volgende(Optional.empty(), 1234, 100, 16));
    }

    @Test
    void constructor_ongeldigeOmvangOfMarge_weigert() {
        Opruimtermijnen termijnen = new Opruimtermijnen(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3));

        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 0, 0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalStateException.class, () -> new EventlogPartitiebeheer(null, termijnen, 10, -1, Duration.ofSeconds(1), 1));
    }
}
