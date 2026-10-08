package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        doThrow(transportfout())
                .doNothing()
                .when(callbackClient).stuurStatusUpdate(any());

        adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));

        verify(callbackClient, times(2)).stuurStatusUpdate(any());
    }

    // Na MAX_POGINGEN mislukte pogingen geeft de adapter het op zonder te gooien: de status is al
    // vastgelegd en gecommit, dus gooien redt niets en zou alleen de observer-aanroep laten falen.
    @Test
    void stuurStatusUpdate_allePogingenMislukt_gooitNietMaarStoptNaMaxPogingen() {
        doThrow(new WebApplicationException(503))
                .when(callbackClient).stuurStatusUpdate(any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(ConsumentCallbackAdapter.class)) {
            assertDoesNotThrow(() -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
        assertEquals(1, fouten.size(), "definitief verlies hoort op ERROR gelogd te worden");
    }

    // Alleen een fout aan de kant van de Dienstverlener wordt herhaald. Een fout in de NMC zelf
    // ontsnapt meteen naar StatusUpdateVerzender, die hem op ERROR logt.
    @Test
    void stuurStatusUpdate_foutInDeNmcZelf_ontsnaptZonderHerpoging() {
        doThrow(new IllegalStateException("serialisatie kapot"))
                .when(callbackClient).stuurStatusUpdate(any());

        assertThrows(IllegalStateException.class,
                () -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    // De rest-client pakt ook een fout in de NMC zelf in als ProcessingException. Zonder
    // transportfout als oorzaak hoort die door te gaan naar StatusUpdateVerzender.
    @Test
    void stuurStatusUpdate_processingExceptionZonderTransportfout_ontsnaptZonderHerpoging() {
        doThrow(new ProcessingException(new IllegalStateException("geen serializer")))
                .when(callbackClient).stuurStatusUpdate(any());

        assertThrows(ProcessingException.class,
                () -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    // Een serialisatiefout komt als IOException-subtype binnen, maar is een fout in de NMC zelf.
    @Test
    void stuurStatusUpdate_serialisatiefout_ontsnaptZonderHerpoging() {
        doThrow(new ProcessingException(JsonMappingException.fromUnexpectedIOE(new IOException("geen serializer"))))
                .when(callbackClient).stuurStatusUpdate(any());

        assertThrows(ProcessingException.class,
                () -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    @Test
    void stuurStatusUpdate_timeout_wordtAlsTransportfoutHerhaald() {
        doThrow(new ProcessingException(new TimeoutException("read timeout")))
                .when(callbackClient).stuurStatusUpdate(any());

        assertDoesNotThrow(() -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));

        verify(callbackClient, times(3)).stuurStatusUpdate(any());
    }

    // Een interrupt tijdens de HTTP-aanroep zelf komt ingepakt terug, met de vlag al gewist.
    @Test
    void stuurStatusUpdate_onderbrokenTijdensDeAanroep_stoptHerstelDeVlagEnLogtOpError() {
        doThrow(new ProcessingException(new InterruptedException()))
                .when(callbackClient).stuurStatusUpdate(any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(ConsumentCallbackAdapter.class)) {
            adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(1, fouten.size());
        verify(callbackClient, times(1)).stuurStatusUpdate(any());
    }

    // Een onderbroken thread stopt met herhalen en houdt zijn interrupt-vlag, zodat de aanroeper
    // die nog ziet.
    @Test
    void stuurStatusUpdate_onderbrokenTijdensWachten_stoptEnBehoudtDeInterruptVlag() {
        ConsumentCallbackAdapter adapterMetWachttijd = new ConsumentCallbackAdapter(url -> callbackClient, 60_000L);
        doThrow(transportfout()).when(callbackClient).stuurStatusUpdate(any());

        List<String> fouten;
        Thread.currentThread().interrupt();
        try (LogVanger vanger = LogVanger.van(ConsumentCallbackAdapter.class)) {
            adapterMetWachttijd.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
        assertEquals(1, fouten.size(), "een onderbroken statusupdate is verloren en hoort op ERROR");
    }

    // Een fout bij het sluiten na een geslaagde aflevering mag de update niet als verloren laten melden.
    @Test
    void stuurStatusUpdate_closeGooitNaGeslaagdeAflevering_gooitNietEnLogtOpWarn() {
        doThrow(new IllegalStateException("verbinding al dicht")).when(callbackClient).close();

        List<String> waarschuwingen;
        try (LogVanger vanger = LogVanger.van(ConsumentCallbackAdapter.class)) {
            assertDoesNotThrow(() -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));
            waarschuwingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        verify(callbackClient, times(1)).stuurStatusUpdate(any());
        assertEquals(1, waarschuwingen.size());
    }

    // Elke statusupdate bouwt een eigen client; zonder close() lekken zijn HTTP-verbindingen, ook
    // als er een fout doorgaat naar StatusUpdateVerzender.
    @Test
    void stuurStatusUpdate_sluitDeClient_ookBijEenFoutDieOntsnapt() {
        adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback"));
        verify(callbackClient, times(1)).close();

        doThrow(new IllegalStateException("serialisatie kapot")).when(callbackClient).stuurStatusUpdate(any());
        assertThrows(IllegalStateException.class,
                () -> adapter.stuurStatusUpdate(opdracht("https://omc.example.nl/callback")));
        verify(callbackClient, times(2)).close();
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
        assertEquals("nl.overheid.moz.notificatie.status.niet-bezorgbaar", event.type());
        assertEquals("application/json", event.datacontenttype());
        assertNotNull(event.source());
        assertEquals(opdracht.notificatieId().toString(), event.subject());
        assertEquals(OffsetDateTime.parse("2026-01-15T10:00:00Z"), event.time());
        assertEquals("3", event.sequence());
        assertEquals("Integer", event.sequencetype());
        assertEquals(NotificatieStatus.VERZONDEN, event.data().van());
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, event.data().naar());
        assertEquals(Reden.ONBEREIKBAAR, event.data().reden());
        assertEquals(3L, event.data().versie());
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

    // De controle staat in de constructor en niet bij het versturen: StatusUpdateVerzender pakt de
    // opdracht op in een AFTER_SUCCESS-observer, waar een NPE ná de commit door de transactiemanager
    // wordt opgeslokt. Vanuit de constructor valt dezelfde fout nog binnen de transactie.
    @Test
    void opdracht_zonderNotificatieIdNieuweStatusOfTijdstip_weigert() {
        assertThrows(NullPointerException.class, () -> new StatusUpdateOpdracht(null,
                "https://omc.example.nl/callback", 1L, NotificatieStatus.VERZONDEN, NotificatieStatus.BEZORGD, null, OffsetDateTime.parse("2026-01-15T10:00:00Z")));
        assertThrows(NullPointerException.class, () -> new StatusUpdateOpdracht(UUID.randomUUID(),
                "https://omc.example.nl/callback", 1L, NotificatieStatus.VERZONDEN, null, null, OffsetDateTime.parse("2026-01-15T10:00:00Z")));
        assertThrows(NullPointerException.class, () -> new StatusUpdateOpdracht(UUID.randomUUID(),
                "https://omc.example.nl/callback", 1L, NotificatieStatus.VERZONDEN, NotificatieStatus.BEZORGD, null, null));
    }

    // Een lege callbackUrl is geen fout maar een betekenisdragende waarde: de Dienstverlener heeft
    // geen callback geconfigureerd en vraagt de status zelf op.
    @Test
    void opdracht_zonderCallbackUrl_isToegestaan() {
        assertDoesNotThrow(() -> new StatusUpdateOpdracht(UUID.randomUUID(), null, 1L,
                NotificatieStatus.VERZONDEN, NotificatieStatus.BEZORGD, null, OffsetDateTime.parse("2026-01-15T10:00:00Z")));
    }

    private static ProcessingException transportfout() {
        return new ProcessingException(new ConnectException("Connection refused"));
    }

    private static StatusUpdateOpdracht opdracht(String callbackUrl) {
        return new StatusUpdateOpdracht(UUID.randomUUID(), callbackUrl, 3L,
                NotificatieStatus.VERZONDEN, NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR, OffsetDateTime.parse("2026-01-15T10:00:00Z"));
    }
}
