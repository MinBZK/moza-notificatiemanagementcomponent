package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PogingTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    @Test
    void verwerkReceipt_tweedeBezorging_houdtHetEersteBezorgtijdstip() {
        Poging poging = new Poging(UUID.randomUUID(), 1);

        poging.verwerkReceipt(PogingStatus.BEZORGD, T0);
        poging.verwerkReceipt(PogingStatus.BEZORGD, T0.plusHours(1));

        assertEquals(T0, poging.getBezorgdOp());
    }

    // De vaststellingstermijn loopt vanaf de bezorging die de notificatie opnieuw bezorgd maakte.
    @Test
    void verwerkReceipt_bezorgingNaEenFout_begintEenNieuweTermijn() {
        Poging poging = new Poging(UUID.randomUUID(), 1);

        poging.verwerkReceipt(PogingStatus.BEZORGD, T0);
        poging.verwerkReceipt(PogingStatus.TIJDELIJK_MISLUKT, T0.plusDays(1));
        poging.verwerkReceipt(PogingStatus.BEZORGD, T0.plusDays(2));

        assertEquals(T0.plusDays(2), poging.getBezorgdOp());
    }
}
