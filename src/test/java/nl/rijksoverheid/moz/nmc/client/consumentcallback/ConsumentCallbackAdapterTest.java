package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsumentCallbackAdapterTest {

    private ConsumentCallbackClient callbackClient;
    private ConsumentCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        callbackClient = Mockito.mock(ConsumentCallbackClient.class);
        adapter = new ConsumentCallbackAdapter(url -> callbackClient, 0L);
    }

    @Test
    void stuurStatusUpdate_geenCallbackUrl_doetGeenHttpAanroep() {
        Notificatie notificatie = notificatie(null);

        boolean resultaat = adapter.stuurStatusUpdate(notificatie);

        verifyNoInteractions(callbackClient);
        assertFalse(resultaat);
    }

    @Test
    void stuurStatusUpdate_eerstePoging_succesvol_retourneertTrue() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        boolean resultaat = adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
        assertTrue(resultaat);
    }

    @Test
    void stuurStatusUpdate_eerstePoging_mislukt_tweedePogingSuccesvol_retourneertTrue() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("tijdelijk onbereikbaar"))
                .doNothing()
                .when(callbackClient).stuurStatusUpdate(any());

        boolean resultaat = adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(2)).stuurStatusUpdate(any());
        assertTrue(resultaat);
    }

    @Test
    void stuurStatusUpdate_allePogingenMislukt_retourneertFalse() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("onbereikbaar"))
                .when(callbackClient).stuurStatusUpdate(any());

        boolean resultaat = adapter.stuurStatusUpdate(notificatie);

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
        assertFalse(resultaat);
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
        assertEquals(notificatie.id, event.status().notificatieId());
        assertEquals(NotificatieStatusEnum.DELIVERED, event.status().status());
    }

    private Notificatie notificatie(String callbackUrl) {
        Notificatie notificatie = new Notificatie();
        notificatie.id = UUID.randomUUID();
        notificatie.callbackUrl = callbackUrl;
        notificatie.status = NotificatieStatusEnum.DELIVERED;
        return notificatie;
    }
}
