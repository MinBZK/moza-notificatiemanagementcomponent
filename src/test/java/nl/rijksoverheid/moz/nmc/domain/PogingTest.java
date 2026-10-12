package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PogingTest {

    @Test
    void markeerVerzonden_alVerzonden_weigertEnLaatDeEersteNotifyIdStaan() {
        Poging poging = new Poging(UUID.randomUUID(), 1);
        UUID eerste = UUID.randomUUID();
        poging.markeerVerzonden(eerste, OffsetDateTime.now(ZoneOffset.UTC));

        assertThrows(IllegalStateException.class,
                () -> poging.markeerVerzonden(UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC)));
        assertEquals(eerste, poging.getNotifyId());
        assertEquals(PogingStatus.VERZONDEN, poging.getStatus());
    }
}
