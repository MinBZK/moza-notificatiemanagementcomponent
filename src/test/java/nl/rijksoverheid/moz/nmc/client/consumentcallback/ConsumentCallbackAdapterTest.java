package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

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
        adapter.stuurStatusUpdate(opdracht(null));

        verifyNoInteractions(callbackClient);
    }

    @Test
    void stuurStatusUpdate_eerstePogingSuccesvol_doetGeenHerpoging() {
        adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    @Test
    void stuurStatusUpdate_eerstePogingMislukt_stoptNaEenGeslaagdeHerpoging() {
        doThrow(new RuntimeException("tijdelijk onbereikbaar"))
                .doNothing()
                .when(callbackClient).stuurStatusUpdate(any());

        adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));

        verify(callbackClient, times(2)).stuurStatusUpdate(any());
    }

    // Na MAX_POGINGEN mislukte pogingen geeft de adapter het op zonder te gooien: de status is al
    // vastgelegd en gecommit, dus gooien redt niets en zou alleen de observer-aanroep laten falen.
    @Test
    void stuurStatusUpdate_allePogingenMislukt_gooitNietMaarStoptNaMaxPogingen() {
        doThrow(new RuntimeException("onbereikbaar"))
                .when(callbackClient).stuurStatusUpdate(any());

        assertDoesNotThrow(() -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
    }

    @Test
    void stuurStatusUpdate_event_bevat_correcteData() {
        StatusUpdateOpdracht opdracht = opdracht("https://omc.example.nl/callback");

        adapter.stuurStatusUpdate(opdracht);

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
        assertEquals(opdracht.notificatieId(), event.data().notificatieId());
        assertEquals(StatusWaarde.DELIVERED, event.data().status());
    }

    @Test
    void stuurStatusUpdate_ongeldigeCallbackUrl_gooitNietEnHerhaaltNiet() {
        // Regressietest: clientFactory.maakClient(...) zit buiten de retry-try/catch — een
        // ongeldige URL mag daarom niet uit stuurStatusUpdate ontsnappen. Telt de aanroepen: een
        // permanente fout (ongeldige URL) hoort niet 3x herhaald te worden zoals een tijdelijke.
        int[] aanroepen = {0};
        ConsumentCallbackAdapter adapterMetOngeldigeUrl = new ConsumentCallbackAdapter(
                url -> {
                    aanroepen[0]++;
                    throw new IllegalArgumentException("ongeldige callback-URL: " + url);
                }, 0L);

        assertDoesNotThrow(() -> adapterMetOngeldigeUrl.stuurStatusUpdate(opdracht("niet-een-geldige-url")));

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

        assertThrows(IllegalStateException.class,
                () -> adapterMetKapotteFabriek.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));
    }

    private static StatusUpdateOpdracht opdracht(String callbackUrl) {
        return new StatusUpdateOpdracht(UUID.randomUUID(), callbackUrl, StatusWaarde.DELIVERED);
    }
}
