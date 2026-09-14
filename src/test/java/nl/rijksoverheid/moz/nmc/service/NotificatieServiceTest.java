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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                () -> service.verwerkAfleverstatus(UUID.randomUUID(), "delivered"));

        verifyNoInteractions(statusUpdateEvent);
    }

    @Test
    void verwerkAfleverstatus_bekendeStatus_zetStatusOpNotificatie() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "permanent-failure");

        assertEquals(StatusWaarde.PERMANENT_FAILURE, notificatie.getStatus());
    }

    @Test
    void verwerkAfleverstatus_onbekendeStatus_valtTerugOpOnbekend() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "een-rare-status");

        assertEquals(StatusWaarde.ONBEKEND, notificatie.getStatus());
    }

    // Een status die geen vooruitgang is op de vastgelegde status is geen nieuwe uitkomst maar een
    // dubbele of laat aangekomen callback; die wordt geweigerd zodat de vastgelegde eindstatus blijft
    // staan (en de bewaartermijn niet opnieuw begint te lopen). Zie StatusWaarde#volgtOp.
    @Test
    void verwerkAfleverstatus_vanDefinitieveNaarNietDefinitieveStatus_negeertDeNieuweStatus() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "sending");

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        // Niet alleen de afgeleide status, ook de geschiedenis zelf moet onaangeroerd blijven: een
        // extra (genegeerde) record zou het laatste tijdstip verzetten en daarmee de retentieklok
        // resetten, ook al bleef getStatus() dan toevallig DELIVERED.
        assertEquals(aantalStatussenNaDelivered, notificatie.getStatusGeschiedenis().size());
    }

    // Regressietest. Een late faalstatus ná DELIVERED zijn twee definitieve statussen, en werd door
    // de oude isDefinitief-controle dus doorgelaten: de bezorgde notificatie kwam daarmee alsnog als
    // mislukt in de geschiedenis, ging zo naar de Dienstverlener, en de retentieklok begon opnieuw.
    @Test
    void verwerkAfleverstatus_lateFaalstatusNaDelivered_negeertDeNieuweStatus() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "temporary-failure");

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(aantalStatussenNaDelivered, notificatie.getStatusGeschiedenis().size());
    }

    // NotifyNL herhaalt een callback bij elke niet-2xx, dus precies dezelfde receipt komt in de
    // praktijk meerdere keren binnen. Die hoort geen tweede record op te leveren: dat zou het laatste
    // tijdstip verzetten en de retentieklok resetten zonder dat er iets veranderd is.
    @Test
    void verwerkAfleverstatus_zelfdeStatusTweeKeer_negeertDeHerhaling() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");
        int aantalStatussenNaDelivered = notificatie.getStatusGeschiedenis().size();

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        assertEquals(aantalStatussenNaDelivered, notificatie.getStatusGeschiedenis().size());
    }

    // De statusupdate naar de Dienstverlener hoort bij een geweigerde status óók achterwege te
    // blijven: er is niets nieuws te melden, en een CloudEvent met SENDING zou de Dienstverlener een
    // teruggedraaide bezorgstatus voorspiegelen.
    @Test
    void verwerkAfleverstatus_vanDefinitieveNaarNietDefinitieveStatus_stuurtGeenStatusUpdate() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));
        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        service.verwerkAfleverstatus(UUID.randomUUID(), "sending");

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

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(statusUpdateEvent).fire(captor.capture());
        assertEquals(StatusWaarde.DELIVERED, captor.getValue().status());
        assertEquals("https://omc.example.nl/callback", captor.getValue().callbackUrl());
        assertEquals(notificatie.getId(), captor.getValue().notificatieId());
    }

    // Verwijderen is losgekoppeld van het afleveren van de callback (zie NotificatieRetentieScheduler).
    // De service verstuurt de callback niet eens zelf meer, dus er valt hier niets te variëren op de
    // uitkomst ervan: de notificatie blijft hoe dan ook staan.
    @Test
    void verwerkAfleverstatus_verwijdertNotificatieNiet() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByExternalReference(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        verify(statusUpdateEvent).fire(any());
        verify(notificatieRepository, never()).deleteById(any());
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
