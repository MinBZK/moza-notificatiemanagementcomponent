package nl.rijksoverheid.moz.client.consumentcallback;

import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.domain.Notificatie;
import nl.rijksoverheid.moz.repository.NotificatieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsumentCallbackAdapterTest {

    private ConsumentCallbackClient callbackClient;
    private NotificatieRepository notificatieRepository;
    private ConsumentCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        callbackClient = Mockito.mock(ConsumentCallbackClient.class);
        notificatieRepository = Mockito.mock(NotificatieRepository.class);
        adapter = new ConsumentCallbackAdapter(notificatieRepository, url -> callbackClient);
        adapter.setInitieleWachtMs(0L);
    }

    @Test
    void stuurStatusUpdate_geenCallbackUrl_doetGeenHttpAanroep() {
        Notificatie notificatie = notificatie(null);

        adapter.stuurStatusUpdate(notificatie);

        verifyNoInteractions(callbackClient);
        verifyNoInteractions(notificatieRepository);
    }

    @Test
    void stuurStatusUpdate_eerstePoging_succesvol_verwijdertNotificatie() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
        verify(notificatieRepository, times(1)).delete(notificatie);
    }

    @Test
    void stuurStatusUpdate_eerstePoging_mislukt_tweedePogingSuccesvol_verwijdertNotificatie() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("tijdelijk onbereikbaar"))
                .doNothing()
                .when(callbackClient).stuurStatusUpdate(any());

        adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(2)).stuurStatusUpdate(any());
        verify(notificatieRepository, times(1)).delete(notificatie);
    }

    @Test
    void stuurStatusUpdate_allePogingenMislukt_verwijdertNotificatieNiet() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("onbereikbaar"))
                .when(callbackClient).stuurStatusUpdate(any());

        adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
        verify(notificatieRepository, never()).delete(any());
    }

    @Test
    void stuurStatusUpdate_event_bevat_correcteData() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        adapter.stuurStatusUpdate(notificatie);

        ArgumentCaptor<NotificatieStatusEvent> captor = ArgumentCaptor.forClass(NotificatieStatusEvent.class);
        verify(callbackClient).stuurStatusUpdate(captor.capture());
        NotificatieStatusEvent event = captor.getValue();
        assertNotNull(event.id());
        assertEquals("1.0", event.specversion());
        assertEquals("nl.rijksoverheid.moz.nmc.notificatie.status", event.type());
        assertEquals("application/json", event.datacontenttype());
        assertNotNull(event.source());
        assertNotNull(event.subject());
        assertNotNull(event.time());
        assertEquals(notificatie.id, event.data().notificatieId());
        assertEquals("delivered", event.data().status());
    }

    private Notificatie notificatie(String callbackUrl) {
        Notificatie notificatie = new Notificatie();
        notificatie.id = UUID.randomUUID();
        notificatie.callbackUrl = callbackUrl;
        notificatie.status = NotificatieStatus.DELIVERED;
        return notificatie;
    }
}
