package nl.rijksoverheid.moz.client.consumentcallback;

import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.domain.Notificatie;
import nl.rijksoverheid.moz.repository.NotificatieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

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
        adapter = new ConsumentCallbackAdapter(notificatieRepository, url -> callbackClient, 0L);
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

    private Notificatie notificatie(String callbackUrl) {
        Notificatie notificatie = new Notificatie();
        notificatie.id = UUID.randomUUID();
        notificatie.callbackUrl = callbackUrl;
        notificatie.status = NotificatieStatus.DELIVERED;
        return notificatie;
    }
}
