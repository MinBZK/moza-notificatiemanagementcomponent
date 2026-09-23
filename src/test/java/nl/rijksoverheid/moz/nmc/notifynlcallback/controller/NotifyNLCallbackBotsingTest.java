package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Twee receipts voor dezelfde notificatie die echt tegelijk lopen, met de echte transactiemanager.
 * Bewijst dat de optimistic lock via de oorzakenketen bij de herpoging in de controller aankomt;
 * NotifyNLCallbackControllerHerpogingTest bouwt die inpakking alleen na.
 */
@QuarkusTest
class NotifyNLCallbackBotsingTest {

    // Moet overeenkomen met %test.notify.callback.bearer-token in application.properties
    private static final String CALLBACK_BEARER_TOKEN = "test-callback-token-niet-voor-productie";

    @InjectSpy
    NotificatieRepository notificatieRepository;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void tweeVerschillendeReceipts_leggenBeideVastEnSturenElkEenStatusUpdate() throws Exception {
        UUID referentie = UUID.randomUUID();
        UUID id = maakVerzondenNotificatie(referentie);

        laatBotsen(referentie, "delivered", "permanent-failure");

        assertEquals(List.of(StatusWaarde.CREATED, StatusWaarde.SENDING,
                StatusWaarde.PERMANENT_FAILURE, StatusWaarde.DELIVERED), geschiedenis(id));
        // De teruggerolde eerste poging stuurt niets; alleen de twee commits doen dat.
        assertEquals(List.of(StatusWaarde.PERMANENT_FAILURE, StatusWaarde.DELIVERED), verstuurdeStatussen(2));
    }

    // Een herhaling door NotifyNL die binnenkomt terwijl de eerste nog loopt: de verliezer herleest,
    // ziet zijn status al staan en legt niets vast.
    @Test
    void tweeIdentiekeReceipts_leggenEenRecordVastEnSturenEenStatusUpdate() throws Exception {
        UUID referentie = UUID.randomUUID();
        UUID id = maakVerzondenNotificatie(referentie);

        laatBotsen(referentie, "delivered", "delivered");

        assertEquals(List.of(StatusWaarde.CREATED, StatusWaarde.SENDING, StatusWaarde.DELIVERED), geschiedenis(id));
        assertEquals(List.of(StatusWaarde.DELIVERED), verstuurdeStatussen(1));
    }

    // De eerste lezer wacht na het inlezen tot de tweede receipt gecommit is, en loopt dan op de
    // optimistic lock stuk. Beide receipts horen een 204 te krijgen.
    private void laatBotsen(UUID referentie, String eersteStatus, String tweedeStatus) throws Exception {
        CountDownLatch eersteHeeftGelezen = new CountDownLatch(1);
        CountDownLatch tweedeIsGecommit = new CountDownLatch(1);
        AtomicBoolean eersteLezing = new AtomicBoolean(true);
        AtomicBoolean wachtenVerlopen = new AtomicBoolean(false);
        doAnswer(aanroep -> {
            Object resultaat = aanroep.callRealMethod();

            if (eersteLezing.compareAndSet(true, false)) {
                eersteHeeftGelezen.countDown();
                wachtenVerlopen.set(!tweedeIsGecommit.await(10, TimeUnit.SECONDS));
            }

            return resultaat;
        }).when(notificatieRepository).findByExternalReference(any());

        Future<Integer> eerste = executor.submit(() -> stuurReceipt(referentie, eersteStatus));
        assertTrue(eersteHeeftGelezen.await(10, TimeUnit.SECONDS), "eerste receipt las de notificatie niet");
        int tweede = stuurReceipt(referentie, tweedeStatus);
        tweedeIsGecommit.countDown();

        assertEquals(204, tweede);
        assertEquals(204, eerste.get(10, TimeUnit.SECONDS));
        assertFalse(wachtenVerlopen.get(), "eerste receipt wachtte tevergeefs op de tweede");
        // Eerste lezing, tweede receipt, herlezing na de botsing.
        verify(notificatieRepository, times(3)).findByExternalReference(referentie);
    }

    private UUID maakVerzondenNotificatie(UUID referentie) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie("https://omc.example.nl/callback");
            notificatie.markeerVerzonden(referentie);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });
    }

    private List<StatusWaarde> geschiedenis(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                notificatieRepository.findById(id).getStatusGeschiedenis().stream()
                        .map(NotificatieStatus::status).toList());
    }

    private List<StatusWaarde> verstuurdeStatussen(int aantal) {
        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(consumentCallbackAdapter, times(aantal)).stuurStatusUpdate(captor.capture());

        return captor.getAllValues().stream().map(StatusUpdateOpdracht::status).toList();
    }

    private static int stuurReceipt(UUID referentie, String status) {
        return given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + CALLBACK_BEARER_TOKEN)
                .body("""
                        {"id": "%s", "status": "%s", "completed_at": "2025-01-01T12:00:02Z"}
                        """.formatted(referentie, status))
                .when().post("/api/nmc/v1/notifynl-callback")
                .then().extract().statusCode();
    }
}
