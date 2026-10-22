package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificatieDataTest {

    @Test
    void constructor_zonderNieuweStatus_gooitNullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new NotificatieData(NotificatieStatus.VERZONDEN, null, null, 1L));
    }

    // Het eerste event van een notificatie heeft geen vorige status.
    @Test
    void constructor_zonderVorigeStatus_isToegestaan() {
        assertDoesNotThrow(() -> new NotificatieData(null, NotificatieStatus.AANGENOMEN, null, 0L));
    }
}
