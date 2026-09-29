package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Twee receipts voor dezelfde notificatie die echt tegelijk lopen. De eerste houdt de rijvergrendeling
 * vast terwijl de tweede binnenkomt; de tweede wacht en ziet daarna de uitkomst van de eerste.
 */
@QuarkusTest
class NotifyNLCallbackBotsingTest {

    // Moet overeenkomen met %test.notify.callback.bearer-token in application.properties

    @InjectSpy
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    @Inject
    EventRepository eventRepository;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void tweeOpeenvolgendeReceipts_wordenGeserialiseerd() throws Exception {
        UUID notifyId = verzondenNotificatie();

        laatTegelijkLopen(notifyId, "delivered", "2025-01-01T12:00:01Z", "permanent-failure", "2025-01-01T12:00:02Z");

        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD, NotificatieStatus.NIET_BEZORGBAAR), overgangen(notifyId));
    }

    // Een herhaling door NotifyNL die binnenkomt terwijl de eerste nog loopt: de tweede ziet na het
    // wachten dat de receipt al verwerkt is en doet niets.
    @Test
    void tweeIdentiekeReceipts_voerenEenOvergangUit() throws Exception {
        UUID notifyId = verzondenNotificatie();

        laatTegelijkLopen(notifyId, "delivered", "2025-01-01T12:00:01Z", "delivered", "2025-01-01T12:00:01Z");

        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD), overgangen(notifyId));
    }

    // De tweede receipt is ouder dan de eerste en las de poging al vóór hij op de vergrendeling ging
    // wachten. Alleen door de poging na het vergrendelen opnieuw te lezen ziet hij dat de nieuwere
    // uitkomst er al staat; anders brengt hij de notificatie alsnog naar niet-bezorgbaar.
    @Test
    void oudereReceiptTerwijlDeNieuwereVergrendeldHoudt_wordtGenegeerd() throws Exception {
        UUID notifyId = verzondenNotificatie();

        laatTegelijkLopen(notifyId, "delivered", "2025-01-01T12:00:02Z", "permanent-failure", "2025-01-01T12:00:01Z");

        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD), overgangen(notifyId));
    }

    // De eerste receipt houdt na het vergrendelen een halve seconde vast; de tweede komt in die tijd
    // binnen en moet op de vergrendeling wachten.
    private void laatTegelijkLopen(UUID notifyId, String eersteStatus, String eersteTijdstip,
                                   String tweedeStatus, String tweedeTijdstip) throws Exception {
        CountDownLatch eersteHeeftVergrendeld = new CountDownLatch(1);
        AtomicBoolean eersteAanroep = new AtomicBoolean(true);
        doAnswer(aanroep -> {
            Object resultaat = aanroep.callRealMethod();

            if (eersteAanroep.compareAndSet(true, false)) {
                eersteHeeftVergrendeld.countDown();
                Thread.sleep(500);
            }

            return resultaat;
        }).when(overgangsfunctie).vergrendel(any());

        Future<Integer> eerste = executor.submit(() -> stuurReceipt(notifyId, eersteStatus, eersteTijdstip));
        assertTrue(eersteHeeftVergrendeld.await(10, TimeUnit.SECONDS), "eerste receipt vergrendelde de notificatie niet");
        int tweede = stuurReceipt(notifyId, tweedeStatus, tweedeTijdstip);

        assertEquals(204, eerste.get(10, TimeUnit.SECONDS));
        assertEquals(204, tweede);
    }

    private UUID verzondenNotificatie() {
        UUID notifyId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            overgangsfunctie.neemAan(notificatie);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            poging.markeerVerzonden(notifyId, OffsetDateTime.parse("2025-01-01T12:00:00Z"));
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
        });

        return notifyId;
    }

    private List<NotificatieStatus> overgangen(UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() -> eventRepository
                .findByNotificatie(pogingRepository.findByNotifyId(notifyId).orElseThrow().getNotificatieId())
                .stream().map(Event::getNaar).toList());
    }

    // Sinds de callback eerst opslaat, gebeurt de verwerking in de receipttaak; die roept deze
    // methode aan, dus de botsing speelt zich hier af.
    private int stuurReceipt(UUID notifyId, String status, String completedAt) {
        receiptVerwerker.verwerk(notifyId, null, status, OffsetDateTime.parse(completedAt));

        return 204;
    }
}
