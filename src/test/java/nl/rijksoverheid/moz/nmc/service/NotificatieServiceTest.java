package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.event.Event;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLConfiguratieException;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificatieServiceTest {

    private static final String TEST_TEMPLATE_ID = "test-template-id";

    private ProfielServiceAdapter profielServiceAdapter;
    private NotifyNLVerzendAdapter verzendAdapter;
    private NotificatieRepository notificatieRepository;
    @SuppressWarnings("unchecked")
    private Event<StatusUpdateOpdracht> statusUpdateEvent;
    private NotificatieService service;

    @BeforeEach
    void setUp() {
        profielServiceAdapter = mock(ProfielServiceAdapter.class);
        verzendAdapter = mock(NotifyNLVerzendAdapter.class);
        notificatieRepository = mock(NotificatieRepository.class);
        statusUpdateEvent = mock(Event.class);
        service = new NotificatieService(profielServiceAdapter, verzendAdapter, notificatieRepository, statusUpdateEvent);
    }

    @Test
    void versturen_happyFlow_retourneertNotificatieMetStatusSendingEnNotifyId() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        UUID notifyNlId = UUID.randomUUID();
        when(verzendAdapter.verstuurEmail(eq("burger@example.nl"), eq(TEST_TEMPLATE_ID), eq(Map.of("naam", "Voorbeeld BV")))).thenReturn(notifyNlId);

        Notificatie resultaat = service.versturen(opdracht("https://omc.example.nl/callback"));

        assertEquals(StatusWaarde.SENDING, resultaat.getStatus());
        assertEquals(notifyNlId, resultaat.getExternalReference());
        assertEquals("https://omc.example.nl/callback", resultaat.getCallbackUrl());
    }

    @Test
    void versturen_persisteertEnFlushtVoordatNotifyWordtAangeroepen() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        when(verzendAdapter.verstuurEmail(any(), any(), any())).thenReturn(UUID.randomUUID());

        service.versturen(opdracht(null));

        InOrder inOrder = inOrder(notificatieRepository, verzendAdapter);
        inOrder.verify(notificatieRepository).persist(any(Notificatie.class));
        inOrder.verify(notificatieRepository).flush();
        inOrder.verify(verzendAdapter).verstuurEmail(any(), any(), any());
    }

    @Test
    void versturen_geenEmailadresGevonden_persisteertNietEnRoeptNotifyNietAan() {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenThrow(new GeenEmailadresGevondenException("geen e-mailadres"));

        assertThrows(GeenEmailadresGevondenException.class, () -> service.versturen(opdracht(null)));

        verify(notificatieRepository, never()).persist(any(Notificatie.class));
        verifyNoInteractions(verzendAdapter);
    }

    @Test
    void verstuurDecentraal_happyFlow_verstuurtNaarOpgegevenEmailZonderProfielserviceLookup() throws NotifyNLConfiguratieException, NotifyNLVerzendException {
        UUID notifyNlId = UUID.randomUUID();
        when(verzendAdapter.verstuurEmail(eq("burger@example.nl"), eq(TEST_TEMPLATE_ID), eq(Map.of("naam", "Voorbeeld BV")))).thenReturn(notifyNlId);

        Notificatie resultaat = service.verstuurDecentraal(
                new DecentraleNotificatieVersturenOpdracht("burger@example.nl", TEST_TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), "https://omc.example.nl/callback"));

        assertEquals(StatusWaarde.SENDING, resultaat.getStatus());
        assertEquals(notifyNlId, resultaat.getExternalReference());
        assertEquals("https://omc.example.nl/callback", resultaat.getCallbackUrl());
        verifyNoInteractions(profielServiceAdapter);
    }

    @Test
    void verwerkAfleverstatus_onbekendNotifyNlId_gooitNotificatieNietGevondenException() {
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.empty());

        assertThrows(NotificatieNietGevondenException.class,
                () -> service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null));

        verifyNoInteractions(statusUpdateEvent);
    }

    @Test
    void verwerkAfleverstatus_bekendeStatus_zetStatusOpNotificatie() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "permanent-failure", null);

        assertEquals(StatusWaarde.PERMANENT_FAILURE, notificatie.getStatus());
    }

    @Test
    void verwerkAfleverstatus_onbekendeStatus_valtTerugOpOnbekend() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "een-rare-status", null);

        assertEquals(StatusWaarde.ONBEKEND, notificatie.getStatus());
    }

    // Terugvallen naar de verzendfase na een uitkomst is een laat aangekomen callback; die wordt
    // geweigerd zodat de vastgelegde uitkomst blijft staan. Zie StatusWaarde#volgtOp.
    @Test
    void verwerkAfleverstatus_vanDefinitieveNaarNietDefinitieveStatus_negeertDeNieuweStatus() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "sending", null);

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        // Niet alleen de afgeleide status, ook de geschiedenis zelf moet onaangeroerd blijven: een
        // extra (genegeerde) record zou het laatste tijdstip verzetten, ook al bleef getStatus() dan
        // toevallig DELIVERED.
        assertEquals(aantalStatussenNaDelivered, notificatie.getStatusGeschiedenis().size());
    }

    // NotifyNL kan ná een bezorging alsnog een fout melden. Die hoort de geschiedenis in en naar de
    // Dienstverlener te gaan; welke uitkomst dan telt, is de afhandeling van de statussen zelf en
    // ligt nog niet vast.
    @Test
    void verwerkAfleverstatus_lateFaalstatusNaDelivered_registreertDeNieuweStatus() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "temporary-failure", null);

        assertEquals(StatusWaarde.TEMPORARY_FAILURE, notificatie.getStatus());
        assertEquals(aantalStatussenNaDelivered + 1, notificatie.getStatusGeschiedenis().size());
    }

    // NotifyNL herhaalt een callback bij elke niet-2xx, dus precies dezelfde receipt komt in de
    // praktijk meerdere keren binnen. Die hoort geen tweede record op te leveren: dat zou het laatste
    // tijdstip verzetten zonder dat er iets veranderd is.
    @Test
    void verwerkAfleverstatus_zelfdeStatusTweeKeer_negeertDeHerhaling() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        assertEquals(aantalStatussenNaDelivered, notificatie.getStatusGeschiedenis().size());
    }

    // De statusupdate naar de Dienstverlener hoort bij een geweigerde status óók achterwege te
    // blijven: er is niets nieuws te melden, en een CloudEvent met SENDING zou de Dienstverlener een
    // teruggedraaide bezorgstatus voorspiegelen.
    @Test
    void verwerkAfleverstatus_vanDefinitieveNaarNietDefinitieveStatus_stuurtGeenStatusUpdate() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        service.verwerkAfleverstatus(UUID.randomUUID(), "sending", null);

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(statusUpdateEvent, times(1)).fire(captor.capture());
        assertEquals(StatusWaarde.DELIVERED, captor.getValue().status());
    }

    // De statusupdate gaat als event de deur uit en wordt pas ná de commit verstuurd
    // (StatusUpdateVerzender, AFTER_SUCCESS). Verstuurde de service hem hier zelf, dan zou een
    // mislukte commit — een OptimisticLockException door een gelijktijdige tweede receipt, of een
    // JTA-timeout — de Dienstverlener achterlaten met een status die de NMC heeft teruggerold.
    @Test
    void verwerkAfleverstatus_nieuweStatus_vuurtStatusUpdateOpdrachtAf() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(statusUpdateEvent).fire(captor.capture());
        assertEquals(StatusWaarde.DELIVERED, captor.getValue().status());
        assertEquals("https://omc.example.nl/callback", captor.getValue().callbackUrl());
        assertEquals(notificatie.getId(), captor.getValue().notificatieId());
    }

    // Een geslaagde statusupdate verwijdert de notificatie niet; de statusgeschiedenis blijft staan.
    @Test
    void verwerkAfleverstatus_verwijdertNotificatieNiet() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        verify(statusUpdateEvent).fire(any());
        verify(notificatieRepository, never()).deleteById(any());
    }

    // Een tweede, afwijkende eindstatus is een nieuwe melding en gaat dus ook naar de
    // Dienstverlener: die moet kunnen zien dat NotifyNL op zijn bezorging is teruggekomen.
    @Test
    void verwerkAfleverstatus_tweeVerschillendeEindstatussen_stuurtTweeStatusUpdates() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        service.verwerkAfleverstatus(UUID.randomUUID(), "permanent-failure", null);

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(statusUpdateEvent, times(2)).fire(captor.capture());
        assertEquals(List.of(StatusWaarde.DELIVERED, StatusWaarde.PERMANENT_FAILURE),
                captor.getAllValues().stream().map(StatusUpdateOpdracht::status).toList());
    }

    // Elke nieuwe melding gaat door, ook een status die eerder al voorbijkwam (A, B, A).
    @Test
    void verwerkAfleverstatus_eerdereStatusDieTerugkomt_stuurtOpnieuwEenStatusUpdate() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);
        service.verwerkAfleverstatus(UUID.randomUUID(), "permanent-failure", null);
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(statusUpdateEvent, times(3)).fire(captor.capture());
        assertEquals(List.of(StatusWaarde.DELIVERED, StatusWaarde.PERMANENT_FAILURE, StatusWaarde.DELIVERED),
                captor.getAllValues().stream().map(StatusUpdateOpdracht::status).toList());
        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
    }

    // Twee verschillende onbekende waarden worden allebei ONBEKEND, dus de tweede is een herhaling en
    // gaat niet door. Beide ruwe waarden staan wel op ERROR in het log.
    @Test
    void verwerkAfleverstatus_tweeVerschillendeOnbekendeStatussen_legtDeTweedeNietVastMaarLogtBeide() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(NotificatieService.class)) {
            service.verwerkAfleverstatus(UUID.randomUUID(), "foo", null);
            service.verwerkAfleverstatus(UUID.randomUUID(), "bar", null);
            fouten = vanger.regelsOpNiveau(java.util.logging.Level.SEVERE);
        }

        assertEquals(StatusWaarde.ONBEKEND, notificatie.getStatus());
        verify(statusUpdateEvent, times(1)).fire(any());
        assertEquals(2, fouten.size());
        assertTrue(fouten.get(0).contains("'foo'") && fouten.get(1).contains("'bar'"));
    }

    // NotifyNL herhaalt bij elke niet-2xx, dus dezelfde receipt komt vaker binnen. Dat op WARN loggen
    // leert een operator WARNs negeren.
    @Test
    void verwerkAfleverstatus_herhaaldeReceipt_meldtNietOpWarn() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);

        List<String> waarschuwingen;
        try (LogVanger vanger = LogVanger.van(NotificatieService.class)) {
            service.verwerkAfleverstatus(UUID.randomUUID(), "delivered", null);
            waarschuwingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        assertTrue(waarschuwingen.isEmpty(), "een herhaling is het verwachte geval, geen waarschuwing");
    }

    // Het niveau is hier functionaliteit: een status die de NMC niet kent betekent dat NotifyNL iets
    // terugmeldt waar dit component geen afhandeling voor heeft, en dat hoort meteen op te vallen.
    // Zonder deze assertie kan iemand ERROR naar DEBUG verlagen zonder dat een test valt.
    @Test
    void verwerkAfleverstatus_onbekendeStatus_logtOpErrorMetDeIdentificatoren() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        UUID notifyNlReferentie = UUID.randomUUID();

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(NotificatieService.class)) {
            service.verwerkAfleverstatus(notifyNlReferentie, "een-rare-status", null);
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(1, fouten.size());
        assertTrue(fouten.getFirst().contains("een-rare-status"), "de ruwe waarde hoort erin te staan");
        assertTrue(fouten.getFirst().contains(notificatie.getId().toString()));
        assertTrue(fouten.getFirst().contains(notifyNlReferentie.toString()));
    }

    private NotificatieVersturenOpdracht opdracht(String callbackUrl) {
        return new NotificatieVersturenOpdracht(IdentificatieType.KVK, "12345678",
                "Gemeente Voorbeeld", "Parkeervergunning", TEST_TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), callbackUrl);
    }

    private Notificatie notificatie(String callbackUrl) {
        Notificatie notificatie = new Notificatie(callbackUrl);
        stelIdIn(notificatie, UUID.randomUUID());
        return notificatie;
    }

    // id is @GeneratedValue/getter-only (door JPA gezet bij persist) — in deze pure unit test
    // (geen echte database) wordt het via reflectie gezet zodat de repository-mock op een
    // bekend id kan worden geverifieerd.
    private static void stelIdIn(Notificatie notificatie, UUID id) {
        try {
            Field idField = Notificatie.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(notificatie, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
