package nl.rijksoverheid.moz.nmc.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EventPartitieTest {

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
