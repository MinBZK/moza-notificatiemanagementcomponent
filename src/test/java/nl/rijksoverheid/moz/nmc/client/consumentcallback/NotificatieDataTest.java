package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotificatieDataTest {

    @Test
    void constructor_zonderNotificatieId_gooitNullPointerException() {
        NullPointerException fout = assertThrows(NullPointerException.class,
                () -> new NotificatieData(null, "delivered"));

        assertEquals("notificatieId is verplicht", fout.getMessage());
    }

    @Test
    void constructor_zonderStatus_gooitNullPointerException() {
        UUID notificatieId = UUID.randomUUID();

        NullPointerException fout = assertThrows(NullPointerException.class,
                () -> new NotificatieData(notificatieId, null));

        assertEquals("status is verplicht", fout.getMessage());
    }
}
