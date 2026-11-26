package nl.rijksoverheid.moz.nmc.service.taak;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TaakUitkomstTest {

    @Test
    void uitgesteld_zonderDue_weigert() {
        assertThrows(NullPointerException.class, () -> TaakUitkomst.uitgesteld(null, false));
    }
}
