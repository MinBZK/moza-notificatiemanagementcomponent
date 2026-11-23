package nl.rijksoverheid.moz.nmc.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaakUitkomstTest {

    @Test
    void uitgesteld_zonderDue_weigert() {
        assertThrows(NullPointerException.class, () -> TaakUitkomst.uitgesteld(null, false));
    }
}
