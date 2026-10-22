package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PogingTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-01-15T10:00:00Z");
    private static final OffsetDateTime T2 = T1.plusMinutes(5);
    private static final OffsetDateTime NU = T1.plusHours(1);

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

    @Test
    void verwerkReceipt_eersteReceipt_legtUitkomstEnTijdstipVast() {
        Poging poging = verzondenPoging();

        assertTrue(poging.verwerkReceipt(PogingStatus.BEZORGD, T1));
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(T1, poging.getReceiptTijdstip());
    }

    @Test
    void verwerkReceiptZonderTijdstip_eersteReceipt_krijgtHetMomentVanOntvangst() {
        Poging poging = verzondenPoging();

        assertTrue(poging.verwerkReceiptZonderTijdstip(PogingStatus.BEZORGD, NU));
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(NU, poging.getReceiptTijdstip());
    }

    // De volgorde volgt de klok van NotifyNL, ook als die voorloopt op de eigen klok.
    @Test
    void verwerkReceipt_tijdstipInDeToekomst_wordtGevolgd() {
        Poging poging = verzondenPoging();

        assertTrue(poging.verwerkReceipt(PogingStatus.BEZORGD, NU.plusDays(1)));
        assertEquals(NU.plusDays(1), poging.getReceiptTijdstip());
    }

    @Test
    void verwerkReceipt_nieuwereAndereUitkomst_vervangtDeVastgelegde() {
        Poging poging = verzondenPoging();
        poging.verwerkReceipt(PogingStatus.BEZORGD, T1);

        assertTrue(poging.verwerkReceipt(PogingStatus.PERMANENT_MISLUKT, T2));
        assertEquals(PogingStatus.PERMANENT_MISLUKT, poging.getStatus());
        assertEquals(T2, poging.getReceiptTijdstip());
    }

    @Test
    void verwerkReceipt_oudereReceipt_verandertNiets() {
        Poging poging = verzondenPoging();
        poging.verwerkReceipt(PogingStatus.BEZORGD, T2);

        assertFalse(poging.verwerkReceipt(PogingStatus.PERMANENT_MISLUKT, T1));
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(T2, poging.getReceiptTijdstip());
    }

    // Dezelfde uitkomst nog eens, ook met een later tijdstip: het eerste tijdstip is het afleverbewijs.
    @Test
    void verwerkReceipt_zelfdeUitkomstMetLaterTijdstip_isEenHerhaling() {
        Poging poging = verzondenPoging();
        poging.verwerkReceipt(PogingStatus.BEZORGD, T1);

        assertFalse(poging.verwerkReceipt(PogingStatus.BEZORGD, T2));
        assertEquals(T1, poging.getReceiptTijdstip());
    }

    // Een receipt zonder tijdstip zou met het moment van ontvangst altijd de nieuwste zijn.
    @Test
    void verwerkReceiptZonderTijdstip_naEenVastgelegdeUitkomst_verandertNiets() {
        Poging poging = verzondenPoging();
        poging.verwerkReceipt(PogingStatus.BEZORGD, T1);

        assertFalse(poging.verwerkReceiptZonderTijdstip(PogingStatus.PERMANENT_MISLUKT, NU.plusHours(1)));
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(T1, poging.getReceiptTijdstip());
    }

    private static Poging verzondenPoging() {
        Poging poging = new Poging(UUID.randomUUID(), 1);
        poging.markeerVerzonden(UUID.randomUUID(), T1.minusMinutes(1));

        return poging;
    }
}
