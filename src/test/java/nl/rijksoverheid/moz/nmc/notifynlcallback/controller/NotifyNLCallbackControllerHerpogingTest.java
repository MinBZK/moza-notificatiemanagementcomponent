package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.RollbackException;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Bewaakt de herpoging op een botsing tussen twee gelijktijdige delivery receipts voor dezelfde
 * notificatie. Zonder die herpoging ontsnapt de OptimisticLockException en krijgt NotifyNL een 5xx;
 * dat kost een van de 5 herpogingen die NotifyNL doet (met 5 minuten ertussen) aan iets wat puur
 * intern is, en na de vijfde is de receipt daar weg.
 * <p>
 * Losse klasse en geen @QuarkusTest: de optimistic lock slaat pas toe bij de commit van
 * NotificatieService#verwerkAfleverstatus, dus die moet hier een mock zijn die het gedrag nabootst.
 */
class NotifyNLCallbackControllerHerpogingTest {

    private NotificatieService notificatieService;
    private NotifyNLCallbackController controller;

    @BeforeEach
    void setUp() {
        notificatieService = mock(NotificatieService.class);
        controller = new NotifyNLCallbackController(notificatieService);
    }

    // Zoals de exception hier echt aankomt: de optimistic lock slaat toe achter de
    // @Transactional-interceptor, dus ingepakt door de JTA-transactiemanager. Het matchen gebeurt op
    // de oorzakenketen, niet op het type van buiten.
    @Test
    void verwerkAfleverstatus_botsingDieDaarnaSlaagt_probeertOpnieuwEnGooitNiet() {
        doThrow(ingepakteOptimisticLock())
                .doNothing()
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        assertDoesNotThrow(() -> controller.verwerkAfleverstatus(receipt()));

        verify(notificatieService, times(2)).verwerkAfleverstatus(any(), any(), any());
    }

    // Houdt de botsing aan, dan wint uiteindelijk de 5xx alsnog: beter dat NotifyNL het over vijf
    // minuten nog eens probeert dan dat de NMC hier blijft hangen. Begrensd op MAX_POGINGEN.
    @Test
    void verwerkAfleverstatus_botsingDieBlijftAanhouden_gooitNaMaxPogingen() {
        doThrow(ingepakteOptimisticLock())
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        assertThrows(RuntimeException.class, () -> controller.verwerkAfleverstatus(receipt()));

        verify(notificatieService, times(3)).verwerkAfleverstatus(any(), any(), any());
    }

    // De herpoging is bewust smal. Een willekeurige fout drie keer herhalen levert alleen vertraging
    // op, en zou een echte storing als een concurrentieprobleem laten lijken.
    @Test
    void verwerkAfleverstatus_andereFout_wordtNietHerhaald() {
        doThrow(new IllegalStateException("iets anders kapot"))
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        assertThrows(IllegalStateException.class, () -> controller.verwerkAfleverstatus(receipt()));

        verify(notificatieService, times(1)).verwerkAfleverstatus(any(), any(), any());
    }

    @Test
    void verwerkAfleverstatus_zonderBotsing_verwerktEenKeer() {
        doNothing().when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        controller.verwerkAfleverstatus(receipt());

        verify(notificatieService, times(1)).verwerkAfleverstatus(any(), any(), any());
    }

    // completed_at wint van sent_at en created_at: het is "the last time the status was updated" en
    // dus het tijdstip van déze status, terwijl de andere twee bij de verzending horen.
    @Test
    void verwerkAfleverstatus_meerdereTijdstippen_geeftCompletedAtDoor() {
        OffsetDateTime completedAt = OffsetDateTime.parse("2025-03-03T10:00:00Z");
        AfleverstatusRequest request = receipt();
        request.setCreatedAt(OffsetDateTime.parse("2025-03-03T09:00:00Z"));
        request.setSentAt(OffsetDateTime.parse("2025-03-03T09:30:00Z"));
        request.setCompletedAt(completedAt);

        controller.verwerkAfleverstatus(request);

        verify(notificatieService).verwerkAfleverstatus(request.getId(), "delivered", completedAt);
    }

    // Geen van de tijdstipvelden is verplicht in NotifyNL's callbackschema; dan gaat er null door en
    // valt NotificatieService terug op de eigen klok.
    @Test
    void verwerkAfleverstatus_zonderTijdstippen_geeftNullDoor() {
        AfleverstatusRequest request = receipt();

        controller.verwerkAfleverstatus(request);

        verify(notificatieService).verwerkAfleverstatus(request.getId(), "delivered", null);
    }

    private static RuntimeException ingepakteOptimisticLock() {
        RuntimeException ingepakt = new RuntimeException("transactie teruggerold",
                new RollbackException("optimistic lock"));
        ingepakt.getCause().initCause(new OptimisticLockException("rij is inmiddels gewijzigd"));

        return ingepakt;
    }

    private static AfleverstatusRequest receipt() {
        return new AfleverstatusRequest().id(UUID.randomUUID()).status("delivered");
    }
}
