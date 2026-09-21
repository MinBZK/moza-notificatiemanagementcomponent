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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    @Test
    void tweeGelijktijdigeReceipts_leggenBeideVastEnSturenElkEenStatusUpdate() throws Exception {
        UUID referentie = UUID.randomUUID();
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie("https://omc.example.nl/callback");
            notificatie.markeerVerzonden(referentie);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        // De eerste lezer wacht na het inlezen tot de tweede receipt gecommit is.
        CountDownLatch eersteHeeftGelezen = new CountDownLatch(1);
        CountDownLatch tweedeIsGecommit = new CountDownLatch(1);
        AtomicBoolean eersteLezing = new AtomicBoolean(true);
        doAnswer(aanroep -> {
            Object resultaat = aanroep.callRealMethod();
            if (eersteLezing.compareAndSet(true, false)) {
                eersteHeeftGelezen.countDown();
                assertTrue(tweedeIsGecommit.await(10, TimeUnit.SECONDS));
            }

            return resultaat;
        }).when(notificatieRepository).findByExternalReference(any());

        CompletableFuture<Integer> eerste = CompletableFuture.supplyAsync(() -> stuurReceipt(referentie, "delivered"));
        assertTrue(eersteHeeftGelezen.await(10, TimeUnit.SECONDS));
        int tweedeStatus = stuurReceipt(referentie, "permanent-failure");
        tweedeIsGecommit.countDown();

        assertEquals(204, tweedeStatus);
        assertEquals(204, eerste.get(10, TimeUnit.SECONDS));

        // Eerste lezing, tweede receipt, herlezing na de botsing.
        verify(notificatieRepository, times(3)).findByExternalReference(referentie);

        List<StatusWaarde> geschiedenis = QuarkusTransaction.requiringNew().call(() ->
                notificatieRepository.findById(id).getStatusGeschiedenis().stream()
                        .map(NotificatieStatus::status).toList());
        assertEquals(List.of(StatusWaarde.CREATED, StatusWaarde.SENDING,
                StatusWaarde.PERMANENT_FAILURE, StatusWaarde.DELIVERED), geschiedenis);

        // De teruggerolde eerste poging stuurt niets; alleen de twee commits doen dat.
        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(consumentCallbackAdapter, times(2)).stuurStatusUpdate(captor.capture());
        assertEquals(List.of(StatusWaarde.PERMANENT_FAILURE, StatusWaarde.DELIVERED),
                captor.getAllValues().stream().map(StatusUpdateOpdracht::status).toList());
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
