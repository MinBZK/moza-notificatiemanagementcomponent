package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Twee receipts voor dezelfde notificatie die echt tegelijk lopen. De eerste houdt de rijvergrendeling
 * vast terwijl de tweede binnenkomt; de tweede wacht en ziet daarna de uitkomst van de eerste.
 */
@QuarkusTest
class NotifyNLCallbackBotsingTest {

    // Moet overeenkomen met %test.notify.callback.bearer-token in application.properties
    private static final String CALLBACK_BEARER_TOKEN = "test-callback-token-niet-voor-productie";

    @InjectSpy
    Overgangsfunctie overgangsfunctie;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

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
    void tweeOpeenvolgendeReceipts_wordenGeserialiseerdEnSturenElkEenStatusUpdate() throws Exception {
        UUID notifyId = verzondenNotificatie();

        laatTegelijkLopen(notifyId, "delivered", "2025-01-01T12:00:01Z", "permanent-failure", "2025-01-01T12:00:02Z");

        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD, NotificatieStatus.NIET_BEZORGBAAR), overgangen(notifyId));
        assertEquals(List.of(3L, 4L), verstuurdeVersies(2));
    }

    // Een herhaling door NotifyNL die binnenkomt terwijl de eerste nog loopt: de tweede ziet na het
    // wachten dat de receipt al verwerkt is en doet niets.
    @Test
    void tweeIdentiekeReceipts_voerenEenOvergangUitEnSturenEenStatusUpdate() throws Exception {
        UUID notifyId = verzondenNotificatie();

        laatTegelijkLopen(notifyId, "delivered", "2025-01-01T12:00:01Z", "delivered", "2025-01-01T12:00:01Z");

        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD), overgangen(notifyId));
        assertEquals(List.of(3L), verstuurdeVersies(1));
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
        assertEquals(List.of(3L), verstuurdeVersies(1));
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
            Notificatie notificatie = new Notificatie("https://omc.example.nl/callback");
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

    private List<Long> verstuurdeVersies(int aantal) {
        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(consumentCallbackAdapter, times(aantal)).stuurStatusUpdate(captor.capture());

        return captor.getAllValues().stream().map(StatusUpdateOpdracht::versie).toList();
    }

    private static int stuurReceipt(UUID notifyId, String status, String completedAt) {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + CALLBACK_BEARER_TOKEN)
                .body("""
                        {"id": "%s", "status": "%s", "completed_at": "%s"}
                        """.formatted(notifyId, status, completedAt))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then().extract().statusCode();
    }
}
