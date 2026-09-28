package nl.rijksoverheid.moz.nmc.notifynlcallback.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.RollbackException;
import org.hibernate.StaleObjectStateException;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    // Hibernate gooit bij een versiebotsing op een @ElementCollection typisch een
    // StaleObjectStateException, die niet altijd naar OptimisticLockException wordt vertaald. Zonder
    // deze test dekt de suite alleen het andere type, terwijl dit mogelijk juist het productiepad is.
    @Test
    void verwerkAfleverstatus_staleStateException_wordtOokAlsBotsingHerkend() {
        RuntimeException ingepakt = new RuntimeException("transactie teruggerold",
                new StaleObjectStateException("Notificatie", UUID.randomUUID()));
        doThrow(ingepakt)
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

    // De tussensporten van de terugvalladder. Alleen de twee uitersten testen laat toe dat sent_at
    // of created_at uit de keten verdwijnt zonder dat er iets faalt, waarna zo'n receipt stilzwijgend
    // de eigen klok van de NMC als gebeurtenistijd krijgt — precies de kolom die het afleverbewijs
    // voedt, en een verkeerde waarde daar is niet te detecteren en niet te herstellen.
    @Test
    void verwerkAfleverstatus_zonderCompletedAt_valtTerugOpSentAt() {
        OffsetDateTime sentAt = OffsetDateTime.parse("2025-03-03T09:30:00Z");
        AfleverstatusRequest request = receipt();
        request.setCreatedAt(OffsetDateTime.parse("2025-03-03T09:00:00Z"));
        request.setSentAt(sentAt);

        controller.verwerkAfleverstatus(request);

        verify(notificatieService).verwerkAfleverstatus(request.getId(), "delivered", sentAt);
    }

    @Test
    void verwerkAfleverstatus_alleenCreatedAt_valtTerugOpCreatedAt() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2025-03-03T09:00:00Z");
        AfleverstatusRequest request = receipt();
        request.setCreatedAt(createdAt);

        controller.verwerkAfleverstatus(request);

        verify(notificatieService).verwerkAfleverstatus(request.getId(), "delivered", createdAt);
    }

    // Geen van de tijdstipvelden is verplicht in NotifyNL's callbackschema; dan gaat er null door en
    // valt NotificatieService terug op de eigen klok.
    @Test
    void verwerkAfleverstatus_zonderTijdstippen_geeftNullDoor() {
        AfleverstatusRequest request = receipt();

        controller.verwerkAfleverstatus(request);

        verify(notificatieService).verwerkAfleverstatus(request.getId(), "delivered", null);
    }

    // Opgeven is het enige moment in dit pad met blijvend verlies: er gaat een 5xx naar NotifyNL en
    // dat kost een van hun vijf herpogingen, waarna de afleverstatus daar weg is. Zonder deze
    // assertie kan de melding verdwijnen en ziet een operator alleen twee INFO-regels en daarna een
    // generieke 500 uit RESTEasy's eigen logcategorie.
    @Test
    void verwerkAfleverstatus_opgevenNaMaxPogingen_logtOpError() {
        doThrow(ingepakteOptimisticLock())
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());
        AfleverstatusRequest request = receipt();

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(NotifyNLCallbackController.class)) {
            assertThrows(RuntimeException.class, () -> controller.verwerkAfleverstatus(request));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(1, fouten.size());
        assertTrue(fouten.getFirst().contains(request.getId().toString()));
    }

    // Een fout die herhalen niet oplost gaat meteen door, en hoort een andere melding te geven: die
    // twee vragen om verschillend onderzoek.
    @Test
    void verwerkAfleverstatus_nietHerhaalbareFout_logtOpErrorZonderTeHerhalen() {
        doThrow(new IllegalStateException("iets anders kapot"))
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(NotifyNLCallbackController.class)) {
            assertThrows(IllegalStateException.class, () -> controller.verwerkAfleverstatus(receipt()));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(1, fouten.size());
        verify(notificatieService, times(1)).verwerkAfleverstatus(any(), any(), any());
    }

    // Een onbekende referentie is geen verwerkingsfout: alleen een WARN, geen ERROR met stacktrace.
    @Test
    void verwerkAfleverstatus_onbekendeNotificatie_logtAlleenOpWarn() {
        doThrow(new NotificatieNietGevondenException("onbekend"))
                .when(notificatieService).verwerkAfleverstatus(any(), any(), any());

        List<String> fouten;
        List<String> waarschuwingen;
        try (LogVanger vanger = LogVanger.van(NotifyNLCallbackController.class)) {
            assertThrows(RuntimeException.class, () -> controller.verwerkAfleverstatus(receipt()));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
            waarschuwingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        assertEquals(List.of(), fouten);
        assertEquals(1, waarschuwingen.size());
        verify(notificatieService, times(1)).verwerkAfleverstatus(any(), any(), any());
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
