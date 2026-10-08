package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Welke partitie Partitiebeheer als volgende aanmaakt; de DDL zelf staat in PartitiebeheerTest.
class PartitieplanTest {

    private static final EventPartitie LOPEND = EventPartitie.vanaf(1000, 2000);

    @Test
    void volgende_zonderBereikpartitie_begintBijDeVolgendeXid() {
        assertEquals(Optional.of(EventPartitie.vanaf(1234, 1334)), Partitiebeheer.volgende(Optional.empty(), 1234, 100, 0));
    }

    @Test
    void volgende_onder80Procent_maaktGeenNieuwe() {
        assertEquals(Optional.empty(), Partitiebeheer.volgende(Optional.of(LOPEND), 1799, 1000, 10));
    }

    @Test
    void volgende_op80Procent_sluitAanOpDeLopende() {
        assertEquals(Optional.of(EventPartitie.vanaf(2000, 2500)), Partitiebeheer.volgende(Optional.of(LOPEND), 1800, 500, 10));
    }

    @Test
    void volgende_lopendeVol_begintBovenDeUitgedeeldeXids() {
        assertEquals(Optional.of(EventPartitie.vanaf(2000, 3000)), Partitiebeheer.volgende(Optional.of(LOPEND), 2000, 1000, 0));
        assertEquals(Optional.of(EventPartitie.vanaf(2345, 3345)), Partitiebeheer.volgende(Optional.of(LOPEND), 2345, 1000, 0));
    }

    // Een event dat tijdens het aanmaken binnenkomt, mag niet in het nieuwe bereik vallen.
    @Test
    void volgende_achterstand_begintEenMargeBovenDeVolgendeXid() {
        assertEquals(Optional.of(EventPartitie.vanaf(2400, 3400)), Partitiebeheer.volgende(Optional.of(LOPEND), 2345, 1000, 55));
        assertEquals(Optional.of(EventPartitie.vanaf(1250, 1350)), Partitiebeheer.volgende(Optional.empty(), 1234, 100, 16));
    }

    @Test
    void eventPartitie_onverwachteNaam_weigert() {
        assertThrows(IllegalArgumentException.class, () -> new EventPartitie("event; DROP TABLE notificatie", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new EventPartitie("event_standaard", 0, 1));
    }

    @Test
    void eventPartitie_leegOfNegatiefBereik_weigert() {
        assertThrows(IllegalArgumentException.class, () -> EventPartitie.vanaf(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new EventPartitie("event_1", -1, 10));
    }
}
