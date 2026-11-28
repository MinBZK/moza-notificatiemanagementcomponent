package nl.rijksoverheid.moz.nmc.service.receipt;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.taak.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.taak.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.taak.TaakUitkomst;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Quarkus alleen voor de transactie die de handler opent; de afhankelijkheden zijn mocks. De verwerking
// met een database staat in ReceiptReferentieTest.
@QuarkusTest
class ReceiptTaakHandlerTest {

    private static final UUID POGING_ID = UUID.randomUUID();
    private static final UUID NOTIFY_ID = UUID.randomUUID();

    private final ReceiptVerwerker receiptVerwerker = mock(ReceiptVerwerker.class);
    private final PogingRepository pogingRepository = mock(PogingRepository.class);
    private final TaakClaimer taakClaimer = mock(TaakClaimer.class);
    private final ReceiptTaakHandler handler = new ReceiptTaakHandler(receiptVerwerker, pogingRepository, taakClaimer);

    // De notificatie, en daarmee de poging, is na de bewaartermijn verwijderd.
    @Test
    void voerUit_pogingBestaatNietMeer_rondtAfZonderVerwerking() {
        Taak taak = taak(payload("delivered", "2026-01-15T10:00:00Z"));
        when(pogingRepository.findById(POGING_ID)).thenReturn(null);

        assertInstanceOf(TaakUitkomst.AlAfgerond.class, handler.voerUit(taak, () -> {}));

        verify(receiptVerwerker, never()).verwerk(any(), anyString(), anyString(), any());
        verify(taakClaimer).rondAf(taak);
    }

    @Test
    void voerUit_metTijdstip_verwerktDeReceiptMetDatTijdstip() {
        Taak taak = taak(payload("delivered", "2026-01-15T10:00:00Z"));
        when(pogingRepository.findById(POGING_ID)).thenReturn(new Poging(UUID.randomUUID(), 1));

        handler.voerUit(taak, () -> {});

        verify(receiptVerwerker)
                .verwerk(
                        NOTIFY_ID,
                        POGING_ID.toString(),
                        "delivered",
                        OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC));
        verify(taakClaimer).rondAf(taak);
    }

    // Een leeg tijdstip is hoe de opslag een receipt zonder tijdstip bewaart; een ontbrekend kan van een
    // taak komen die zonder dat veld is opgeslagen.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void voerUit_zonderTijdstip_verwerktDeReceiptZonderTijdstip(boolean leeg) {
        Taak taak = taak(payload("permanent-failure", leeg ? "" : null));
        when(pogingRepository.findById(POGING_ID)).thenReturn(new Poging(UUID.randomUUID(), 1));

        handler.voerUit(taak, () -> {});

        verify(receiptVerwerker).verwerk(NOTIFY_ID, POGING_ID.toString(), "permanent-failure", null);
        verify(taakClaimer).rondAf(taak);
    }

    private static Map<String, String> payload(String status, String tijdstip) {
        Map<String, String> payload = new HashMap<>();
        payload.put(TaakHandler.PAYLOAD_POGING_ID, POGING_ID.toString());
        payload.put(ReceiptOpslag.PAYLOAD_NOTIFY_ID, NOTIFY_ID.toString());
        payload.put(ReceiptOpslag.PAYLOAD_STATUS, status);

        if (tijdstip != null) {
            payload.put(ReceiptOpslag.PAYLOAD_TIJDSTIP, tijdstip);
        }

        return payload;
    }

    private static Taak taak(Map<String, String> payload) {
        return new Taak(
                TaakSoort.RECEIPT_VERWERKEN,
                null,
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                payload);
    }
}
