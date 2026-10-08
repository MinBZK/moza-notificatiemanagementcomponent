package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** De koppeling van een receipt aan de poging via {@code reference}, het poging-id. */
@QuarkusTest
class ReceiptReferentieTest {

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    // De receipt is er eerder dan de verzend-commit: de poging neemt het NotifyNL-id over en de
    // overgang naar verzonden gebeurt namens de worker, daarna de uitkomst.
    @Test
    void verwerk_opReferenceVoorEenGeplandePoging_neemtHetIdOverEnDoetEerstVerzonden() {
        UUID pogingId = geplandePoging();
        UUID notifyId = UUID.randomUUID();

        receiptVerwerker.verwerk(notifyId, pogingId.toString(), "delivered", OffsetDateTime.now(ZoneOffset.UTC));

        Poging poging = poging(pogingId);
        assertEquals(notifyId, poging.getNotifyId());
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD), overgangen(poging.getNotificatieId()));
    }

    // Een tweede verzending na een herclaim: het afwijkende id is een duplicaat van dezelfde poging.
    @Test
    void verwerk_opReferenceMetAfwijkendNotifyId_legtDuplicaatVastEnVerwerktDeReceipt() {
        UUID pogingId = geplandePoging();
        UUID eerste = UUID.randomUUID();
        UUID tweede = UUID.randomUUID();
        receiptVerwerker.verwerk(eerste, pogingId.toString(), "sending", OffsetDateTime.now(ZoneOffset.UTC));

        receiptVerwerker.verwerk(tweede, pogingId.toString(), "delivered", OffsetDateTime.now(ZoneOffset.UTC));

        Poging poging = poging(pogingId);
        assertEquals(eerste, poging.getNotifyId());
        assertEquals(List.of(tweede), poging.getDuplicaatIds());
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(NotificatieStatus.BEZORGD, notificatie(poging.getNotificatieId()).getStatus());
    }

    @Test
    void verwerk_zonderReference_valtTerugOpHetNotifyId() {
        UUID pogingId = geplandePoging();
        UUID notifyId = UUID.randomUUID();
        receiptVerwerker.verwerk(notifyId, pogingId.toString(), "sending", OffsetDateTime.now(ZoneOffset.UTC));

        receiptVerwerker.verwerk(notifyId, null, "delivered", OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(PogingStatus.BEZORGD, poging(pogingId).getStatus());
    }

    @Test
    void verwerk_onbruikbareReferenceEnOnbekendNotifyId_gooitNietGevonden() {
        assertThrows(NotificatieNietGevondenException.class,
                () -> receiptVerwerker.verwerk(UUID.randomUUID(), "geen-uuid", "delivered", null));
        assertThrows(NotificatieNietGevondenException.class,
                () -> receiptVerwerker.verwerk(UUID.randomUUID(), UUID.randomUUID().toString(), "delivered", null));
    }

    private UUID geplandePoging() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, null);
            overgangsfunctie.neemAan(notificatie);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);

            return poging.getId();
        });
    }

    private Poging poging(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findById(id));
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    private List<NotificatieStatus> overgangen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList());
    }
}
