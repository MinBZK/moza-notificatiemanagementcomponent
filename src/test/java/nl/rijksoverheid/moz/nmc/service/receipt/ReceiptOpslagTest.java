package nl.rijksoverheid.moz.nmc.service.receipt;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// De opslag met een database staat in ReceiptReferentieTest; hier de notificatie die tussen het vinden
// van de poging en het lezen van de notificatie is verwijderd.
class ReceiptOpslagTest {

    private final PogingRepository pogingRepository = mock(PogingRepository.class);
    private final NotificatieRepository notificatieRepository = mock(NotificatieRepository.class);
    private final TaakRepository taakRepository = mock(TaakRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ReceiptOpslag receiptOpslag = new ReceiptOpslag(pogingRepository, notificatieRepository, taakRepository,
            meterRegistry, Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void slaOp_notificatieIntussenVerwijderd_slaatNietsOpEnTeltDeReceiptAlsAfgewezen() {
        UUID notifyId = UUID.randomUUID();
        Poging poging = new Poging(UUID.randomUUID(), 1);
        when(pogingRepository.zoekVoorReceipt(notifyId, null)).thenReturn(Optional.of(poging));
        when(notificatieRepository.findById(poging.getNotificatieId())).thenReturn(null);

        assertEquals(ReceiptOpslag.Uitkomst.GEEN_POGING, receiptOpslag.slaOp(notifyId, null, "delivered", null));

        assertEquals(1.0, meterRegistry.counter("nmc.receipts.afgewezen").count());
        verifyNoInteractions(taakRepository);
    }
}
