package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonMappingException;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumentCallbackAdapterTest {

    private static final String URL = "https://omc.example.nl/webhook";

    private ConsumentCallbackClient client;
    private ConsumentCallbackAdapter adapter;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ConsumentCallbackClient.class);
        when(client.lever(any(), any(), any(), any())).thenReturn(Uni.createFrom().voidItem());
        adapter = new ConsumentCallbackAdapter(client, Duration.ofSeconds(5));
    }

    @Test
    void lever_2xx_doetEenAanroepMetUrlEnHeaders() {
        adapter.lever(URL, "Bearer jwt", "cursor", events());

        verify(client, times(1)).lever(URL, "Bearer jwt", "cursor", events());
    }

    // Eén poging: de terugkoppeltaak bepaalt wanneer het opnieuw gaat.
    @Test
    void lever_geen2xx_gooitLeveringExceptionNaEenAanroep() {
        geeftFout(new WebApplicationException(503));

        WebhookLeveringException fout = assertThrows(WebhookLeveringException.class,
                () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));

        assertTrue(fout.getMessage().contains("503"));
        verify(client, times(1)).lever(any(), any(), any(), any());
    }

    @Test
    void lever_transportfout_gooitLeveringException() {
        geeftFout(new ProcessingException(new ConnectException("geweigerd")));

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
    }

    @Test
    void lever_timeout_gooitLeveringException() {
        geeftFout(new ProcessingException(new TimeoutException()));

        assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
    }

    // Een serialisatiefout ligt aan het NMC, ook al is Jacksons fout een IOException.
    @Test
    void lever_serialisatiefout_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new JsonMappingException(null, "kapot"));
        geeftFout(fout);

        assertSame(fout, assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events())));
    }

    @Test
    void lever_foutZonderTransportoorzaak_gaatOngewijzigdDoor() {
        ProcessingException fout = new ProcessingException(new IllegalStateException("in het NMC"));
        geeftFout(fout);

        assertSame(fout, assertThrows(ProcessingException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events())));
    }

    @Test
    void lever_onderbroken_zetDeInterruptVlagEnGooitLeveringException() {
        geeftFout(new ProcessingException(new IOException(new InterruptedException())));

        try {
            assertThrows(WebhookLeveringException.class, () -> adapter.lever(URL, "Bearer jwt", "cursor", events()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // De read-time-out van de client vangt een webhook die blijft druppelen niet; de totale termijn wel.
    @Test
    void lever_geenAntwoordBinnenDeTermijn_gooitLeveringException() {
        when(client.lever(any(), any(), any(), any())).thenReturn(Uni.createFrom().nothing());
        ConsumentCallbackAdapter kort = new ConsumentCallbackAdapter(client, Duration.ofMillis(100));

        WebhookLeveringException fout = assertThrows(WebhookLeveringException.class,
                () -> kort.lever(URL, "Bearer jwt", "cursor", events()));

        assertTrue(fout.getMessage().contains("niet binnen"));
    }

    @Test
    void lever_zonderUrl_gooitNullPointerException() {
        assertThrows(NullPointerException.class, () -> adapter.lever(null, "Bearer jwt", "cursor", events()));
    }

    private void geeftFout(RuntimeException fout) {
        when(client.lever(any(), any(), any(), any())).thenReturn(Uni.createFrom().failure(fout));
    }

    private static List<NotificatieStatusEvent> events() {
        UUID notificatieId = UUID.fromString("3f1a0000-0000-4000-8000-000000000001");

        return List.of(NotificatieStatusEvent.van(7L, notificatieId, 3L, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD, null, OffsetDateTime.parse("2026-01-15T10:00:00Z")));
    }
}
