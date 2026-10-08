package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConsumentCallbackAdapterTest {

    private static final String URL = "https://omc.example.nl/webhook";

    private ConsumentCallbackClient client;
    private ConsumentCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ConsumentCallbackClient.class);
        adapter = new ConsumentCallbackAdapter(url -> client);
    }

    @Test
    void lever_2xx_doetEenAanroepMetHeadersEnSluitDeClient() {
        adapter.lever(URL, "Bearer jwt", "cursor", events());

        verify(client, times(1)).lever("Bearer jwt", "cursor", events());
        verify(client).close();
    }

    // Eén poging: de terugkoppeltaak bepaalt wanneer het opnieuw gaat.
    @Test
    void lever_geen2xx_gooitLeveringExceptionNaEenAanroep() {
        doThrow(new WebApplicationException(503)).when(client).lever(any(), any(), any());

        WebhookLeveringException fout = assertThrows(WebhookLeveringException.class,
                () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));

        assertTrue(fout.getMessage().contains("503"));
        verify(client, times(1)).lever(any(), any(), any());
        verify(client).close();
    }

    @Test
    void lever_transportfout_gooitLeveringException() {
        doThrow(new ProcessingException(new ConnectException("geweigerd"))).when(client).lever(any(), any(), any());

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
    }

    @Test
    void lever_timeout_gooitLeveringException() {
        doThrow(new ProcessingException(new TimeoutException())).when(client).lever(any(), any(), any());

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
    }

    // Een serialisatiefout ligt aan het NMC, ook al is Jacksons fout een IOException.
    @Test
    void lever_serialisatiefout_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new JsonMappingException(null, "kapot"));
        doThrow(fout).when(client).lever(any(), any(), any());

        assertSame(fout, assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events())));
    }

    @Test
    void lever_foutZonderTransportoorzaak_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new IllegalStateException("in het NMC"));
        doThrow(fout).when(client).lever(any(), any(), any());

        assertSame(fout, assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events())));
    }

    @Test
    void lever_onderbroken_zetDeInterruptVlagEnGooitLeveringException() {
        doThrow(new ProcessingException(new IOException(new InterruptedException()))).when(client).lever(any(), any(), any());

        try {
            assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void lever_clientNietTeBouwen_gooitLeveringException() {
        ConsumentCallbackAdapter zonderClient = new ConsumentCallbackAdapter(url -> {
            throw new RestClientDefinitionException("ongeldig");
        });

        assertThrows(WebhookLeveringException.class, () -> zonderClient.lever(URL, "Bearer jwt", "cursor", events()));
    }

    // Een fout bij het sluiten zegt niets over de levering.
    @Test
    void lever_foutBijSluiten_telNietAlsMislukking() {
        doThrow(new IllegalStateException("sluiten mislukt")).when(client).close();

        assertDoesNotThrow(() -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
    }

    private static List<NotificatieStatusEvent> events() {
        UUID notificatieId = UUID.fromString("3f1a0000-0000-4000-8000-000000000001");

        return List.of(NotificatieStatusEvent.van(7L, notificatieId, 3L, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD, null, OffsetDateTime.parse("2026-01-15T10:00:00Z")));
    }
}
