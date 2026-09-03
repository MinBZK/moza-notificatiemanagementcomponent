package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        adapter.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED);

        verifyNoInteractions(callbackClient);
    }

    @Test
    void stuurStatusUpdate_eerstePogingSuccesvol_doetGeenHerpoging() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        adapter.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED);

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    @Test
    void stuurStatusUpdate_eerstePogingMislukt_stoptNaEenGeslaagdeHerpoging() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("tijdelijk onbereikbaar"))
                .doNothing()
                .when(callbackClient).stuurStatusUpdate(any());

        adapter.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED);

        verify(callbackClient, times(2)).stuurStatusUpdate(any());
    }

    // Na MAX_POGINGEN mislukte pogingen geeft de adapter het op zonder te gooien: de aanroeper zit in
    // een actieve transactie waarvan een net verwerkte NotifyNL-statusupdate anders verloren gaat.
    @Test
    void stuurStatusUpdate_allePogingenMislukt_gooitNietMaarStoptNaMaxPogingen() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");
        doThrow(new RuntimeException("onbereikbaar"))
                .when(callbackClient).stuurStatusUpdate(any());

        assertDoesNotThrow(() -> adapter.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED));

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
    }

    @Test
    void stuurStatusUpdate_event_bevat_correcteData() {
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        adapter.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED);

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
        assertEquals(notificatie.getId(), event.data().notificatieId());
        assertEquals(StatusWaarde.DELIVERED, event.data().status());
    }

    @Test
    void stuurStatusUpdate_ongeldigeCallbackUrl_gooitNietEnHerhaaltNiet() {
        // Regressietest: clientFactory.maakClient(...) zit buiten de retry-try/catch — een
        // ongeldige URL mag daarom niet uit stuurStatusUpdate ontsnappen, want dat zou de
        // aanroepende @Transactional-methode in NotificatieService laten rollbacken (zie de TODO
        // #732-toelichting daar). Telt de aanroepen: een permanente fout (ongeldige URL) hoort niet
        // 3x herhaald te worden zoals een tijdelijke.
        int[] aanroepen = {0};
        ConsumentCallbackAdapter adapterMetOngeldigeUrl = new ConsumentCallbackAdapter(
                url -> {
                    aanroepen[0]++;
                    throw new IllegalArgumentException("ongeldige callback-URL: " + url);
                }, 0L);
        Notificatie notificatie = notificatie("niet-een-geldige-url");

        assertDoesNotThrow(() -> adapterMetOngeldigeUrl.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED));

        assertEquals(1, aanroepen[0]);
    }

    // Tegenhanger van de test hierboven: de catch rond het bouwen van de client is bewust smal
    // (IllegalArgumentException | RestClientDefinitionException). Een andere fout uit de
    // rest-client-extensie (kapotte truststore, proxyconfiguratie) zegt niets over de meegegeven URL
    // en mag dus niet als "ongeldige callback-URL" weggemoffeld worden.
    @Test
    void stuurStatusUpdate_clientfabriekGooitAndereRuntimeException_ontsnaptWel() {
        ConsumentCallbackAdapter adapterMetKapotteFabriek = new ConsumentCallbackAdapter(
                url -> {
                    throw new IllegalStateException("truststore niet leesbaar");
                }, 0L);
        Notificatie notificatie = notificatie("https://omc.example.nl/callback");

        assertThrows(IllegalStateException.class,
                () -> adapterMetKapotteFabriek.stuurStatusUpdate(notificatie, StatusWaarde.DELIVERED));
    }

    private Notificatie notificatie(String callbackUrl) {
        Notificatie notificatie = new Notificatie(callbackUrl);
        stelIdIn(notificatie, UUID.randomUUID());
        notificatie.registreerStatus(StatusWaarde.DELIVERED);
        return notificatie;
    }

    // id is @GeneratedValue/getter-only (door JPA gezet bij persist) — in deze pure unit test
    // (geen echte database) wordt het via reflectie gezet zodat we kunnen verifiëren dat het
    // wordt doorgegeven aan de callback-event.
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
