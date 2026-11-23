package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class OntvangerTest {

    @Test
    void toString_toontAlleenDeSoort() {
        Ontvanger ontvanger = new Ontvanger(Ontvanger.Soort.BSN, "123456782");

        assertEquals("Ontvanger[soort=BSN]", ontvanger.toString());
        assertFalse(ontvanger.toString().contains("123456782"));
    }

    @Test
    void toString_emailadres_toontDeWaardeNiet() {
        assertFalse(Ontvanger.email("burger@example.nl").toString().contains("burger@example.nl"));
    }
}
