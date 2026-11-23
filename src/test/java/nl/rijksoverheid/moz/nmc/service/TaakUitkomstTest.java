package nl.rijksoverheid.moz.nmc.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class TaakUitkomstTest {

    @Test
    void uitgesteld_zonderDue_weigert() {
        assertThrows(NullPointerException.class, () -> TaakUitkomst.uitgesteld(null, false));
    }
}
