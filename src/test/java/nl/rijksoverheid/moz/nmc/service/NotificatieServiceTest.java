package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.common.IdentificatieType;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificatieServiceTest {

    private ProfielServiceAdapter profielServiceAdapter;
    private NotifyNLVerzendAdapter verzendAdapter;
    private NotificatieRepository notificatieRepository;
    private ConsumentCallbackAdapter consumentCallbackAdapter;
    private NotificatieService service;

    @BeforeEach
    void setUp() {
        profielServiceAdapter = mock(ProfielServiceAdapter.class);
        verzendAdapter = mock(NotifyNLVerzendAdapter.class);
        notificatieRepository = mock(NotificatieRepository.class);
        consumentCallbackAdapter = mock(ConsumentCallbackAdapter.class);
        service = new NotificatieService(profielServiceAdapter, verzendAdapter, notificatieRepository, consumentCallbackAdapter);
    }

    @Test
    void versturen_happyFlow_retourneertNotificatieMetStatusSendingEnNotifyId() {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        UUID notifyNlId = UUID.randomUUID();
        when(verzendAdapter.verstuurEmail("burger@example.nl", Map.of("naam", "Voorbeeld BV"))).thenReturn(notifyNlId);

        Notificatie resultaat = service.versturen(opdracht("https://omc.example.nl/callback"));

        assertEquals(NotificatieStatus.SENDING, resultaat.getStatus());
        assertEquals(notifyNlId, resultaat.getNotifyNlNotificatieId());
        assertEquals("https://omc.example.nl/callback", resultaat.getCallbackUrl());
    }

    @Test
    void versturen_persisteertEnFlushtVoordatNotifyWordtAangeroepen() {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        when(verzendAdapter.verstuurEmail(any(), any())).thenReturn(UUID.randomUUID());

        service.versturen(opdracht(null));

        InOrder inOrder = inOrder(notificatieRepository, verzendAdapter);
        inOrder.verify(notificatieRepository).persist(any(Notificatie.class));
        inOrder.verify(notificatieRepository).flush();
        inOrder.verify(verzendAdapter).verstuurEmail(any(), any());
    }

    @Test
    void versturen_geenEmailadresGevonden_persisteertNietEnRoeptNotifyNietAan() {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenThrow(new GeenEmailadresGevondenException("geen e-mailadres"));

        assertThrows(GeenEmailadresGevondenException.class, () -> service.versturen(opdracht(null)));

        verify(notificatieRepository, never()).persist(any(Notificatie.class));
        verifyNoInteractions(verzendAdapter);
    }

    @Test
    void verwerkAfleverstatus_onbekendNotifyNlId_gooitNotificatieNietGevondenException() {
        when(notificatieRepository.findByNotifyNlNotificatieId(any())).thenReturn(Optional.empty());

        assertThrows(NotificatieNietGevondenException.class,
                () -> service.verwerkAfleverstatus(UUID.randomUUID(), "delivered"));

        verifyNoInteractions(consumentCallbackAdapter);
    }

    @Test
    void verwerkAfleverstatus_bekendeStatus_zetStatusOpNotificatie() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByNotifyNlNotificatieId(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "permanent-failure");

        assertEquals(NotificatieStatus.PERMANENT_FAILURE, notificatie.getStatus());
    }

    @Test
    void verwerkAfleverstatus_onbekendeStatus_valtTerugOpTechnicalFailure() {
        Notificatie notificatie = notificatie(null);
        when(notificatieRepository.findByNotifyNlNotificatieId(any())).thenReturn(Optional.of(notificatie));

        service.verwerkAfleverstatus(UUID.randomUUID(), "een-rare-status");

        assertEquals(NotificatieStatus.TECHNICAL_FAILURE, notificatie.getStatus());
    }

    @Test
    void verwerkAfleverstatus_callbackSuccesvol_verwijdertNotificatie() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByNotifyNlNotificatieId(any())).thenReturn(Optional.of(notificatie));
        when(consumentCallbackAdapter.stuurStatusUpdate(notificatie)).thenReturn(true);

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        verify(notificatieRepository).deleteById(notificatie.getId());
    }

    @Test
    void verwerkAfleverstatus_callbackMislukt_bewaartNotificatie() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        when(notificatieRepository.findByNotifyNlNotificatieId(any())).thenReturn(Optional.of(notificatie));
        when(consumentCallbackAdapter.stuurStatusUpdate(notificatie)).thenReturn(false);

        service.verwerkAfleverstatus(UUID.randomUUID(), "delivered");

        verify(notificatieRepository, never()).deleteById(any());
    }

    private NotificatieVersturenOpdracht opdracht(String callbackUrl) {
        return new NotificatieVersturenOpdracht(IdentificatieType.KVK, "12345678",
                "Gemeente Voorbeeld", "Parkeervergunning", Map.of("naam", "Voorbeeld BV"), callbackUrl);
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
