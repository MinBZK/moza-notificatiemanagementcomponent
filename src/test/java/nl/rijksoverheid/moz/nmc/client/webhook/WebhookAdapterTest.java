package nl.rijksoverheid.moz.nmc.client.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonMappingException;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@Tag("unit")
class WebhookAdapterTest {

    private static final String URL = "https://omc.example.nl/webhook";
    private WebhookClient client;
    private WebhookAdapter adapter;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(WebhookClient.class);
        when(client.lever(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nullItem());
        adapter = new WebhookAdapter(client, TestConfig.webhook(Duration.ofSeconds(5)));
    }

    @Test
    void lever_2xx_doetEenAanroepMetUrlEnHeaders() {
        adapter.lever(URL, "Bearer jwt", "cursor", body());

        verify(client, times(1)).lever(URL, "Bearer jwt", "cursor", body());
    }

    // De body van het antwoord wordt niet gelezen: sluiten laat de client de rest weggooien.
    @Test
    void lever_2xxMetBody_sluitHetAntwoordZonderHetTeLezen() throws Exception {
        InputStream antwoord = Mockito.mock(InputStream.class);
        when(client.lever(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(antwoord));

        adapter.lever(URL, "Bearer jwt", "cursor", body());

        verify(antwoord).close();
        verifyNoMoreInteractions(antwoord);
    }

    // De status is er al; een fout bij het sluiten maakt de levering niet ongedaan.
    @Test
    void lever_sluitenVanHetAntwoordFaalt_teltAlsAfgeleverd() throws Exception {
        InputStream antwoord = Mockito.mock(InputStream.class);
        Mockito.doThrow(new IOException("verbinding weg")).when(antwoord).close();
        when(client.lever(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(antwoord));

        assertDoesNotThrow(() -> adapter.lever(URL, "Bearer jwt", "cursor", body()));
    }

    // Eén poging: de terugkoppeltaak bepaalt wanneer het opnieuw gaat.
    @Test
    void lever_geen2xx_gooitLeveringExceptionNaEenAanroep() {
        geeftFout(new WebApplicationException(503));

        WebhookLeveringException fout =
                assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body()));

        assertTrue(fout.getMessage().contains("503"));
        verify(client, times(1)).lever(any(), any(), any(), any());
    }

    @Test
    void lever_transportfout_gooitLeveringException() {
        geeftFout(new ProcessingException(new ConnectException("geweigerd")));

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body()));
    }

    @Test
    void lever_timeout_gooitLeveringException() {
        geeftFout(new ProcessingException(new TimeoutException()));

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body()));
    }

    // Een serialisatiefout ligt aan het NMC, ook al is Jacksons fout een IOException.
    @Test
    void lever_serialisatiefout_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new JsonMappingException(null, "kapot"));
        geeftFout(fout);

        assertSame(
                fout,
                assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body())));
    }

    @Test
    void lever_foutZonderTransportoorzaak_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new IllegalStateException("in het NMC"));
        geeftFout(fout);

        assertSame(
                fout,
                assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body())));
    }

    @Test
    void lever_onderbroken_zetDeInterruptVlagEnGooitLeveringException() {
        geeftFout(new ProcessingException(new IOException(new InterruptedException())));

        try {
            assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", body()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // De read-time-out van de client vangt een webhook die blijft druppelen niet; de totale termijn wel.
    @Test
    void lever_geenAntwoordBinnenDeTermijn_gooitLeveringException() {
        when(client.lever(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().nothing());
        WebhookAdapter kort = new WebhookAdapter(client, TestConfig.webhook(Duration.ofMillis(100)));

        WebhookLeveringException fout =
                assertThrows(WebhookLeveringException.class, () -> kort.lever(URL, "Bearer jwt", "cursor", body()));

        assertTrue(fout.getMessage().contains("niet binnen"));
    }

    @Test
    void lever_zonderUrl_gooitNullPointerException() {
        assertThrows(NullPointerException.class, () -> adapter.lever(null, "Bearer jwt", "cursor", body()));
    }

    private void geeftFout(RuntimeException fout) {
        when(client.lever(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().failure(fout));
    }

    private static String body() {
        return "[{\"id\":\"3f1a0000-0000-4000-8000-000000000007\",\"type\":\"nl.mijnoverheidzakelijk.nmc.notificatie.status.bezorgd\"}]";
    }
}
