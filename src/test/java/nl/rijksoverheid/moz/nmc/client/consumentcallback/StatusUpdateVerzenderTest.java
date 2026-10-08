package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pint de transactiefase van de observer vast. Zonder deze tests kan
 * {@code during = TransactionPhase.AFTER_SUCCESS} veranderen in {@code IN_PROGRESS} — of per
 * ongeluk wegvallen, want dat is de CDI-standaard — zonder dat er iets faalt, terwijl die fase de
 * hele reden van bestaan van deze klasse is.
 */
@QuarkusTest
class StatusUpdateVerzenderTest {

    @Inject
    Event<StatusUpdateOpdracht> statusUpdateEvent;

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    // De kern: bij een teruggerolde transactie mag de Dienstverlener niets horen. Zou de observer op
    // IN_PROGRESS staan, dan kreeg hij een status die de NMC daarna terugdraait — precies wat de
    // herpoging in NotifyNLCallbackController actief uitlokt (poging 1 rolt terug, poging 2 slaagt).
    @Test
    void afgevuurdInEenTransactieDieTerugrolt_verstuurtNiets() {
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            statusUpdateEvent.fire(opdracht("https://omc.example.nl/callback"));

            throw new IllegalStateException("gesimuleerde storing ná het afvuren");
        }));

        verifyNoInteractions(consumentCallbackAdapter);
    }

    // Tegenhanger: zonder deze zou de test hierboven ook slagen als de observer nooit vuurt.
    @Test
    void afgevuurdInEenTransactieDieCommit_verstuurtWel() {
        QuarkusTransaction.requiringNew().run(() ->
                statusUpdateEvent.fire(opdracht("https://omc.example.nl/callback")));

        verify(consumentCallbackAdapter, times(1)).stuurStatusUpdate(any());
    }

    // ConsumentCallbackAdapter laat een fout in de NMC zelf bewust ontsnappen. Die komt hier aan ná
    // de commit, waar de transactiemanager elke Throwable zelf opslokt; de observer moet hem daarom
    // afvangen en loggen in plaats van hem te laten verdwijnen. De aanroeper mag er niets van merken.
    @Test
    void alsDeAdapterGooit_ontsnaptDatNietUitDeCommit() {
        doThrow(new IllegalStateException("truststore niet leesbaar"))
                .when(consumentCallbackAdapter).stuurStatusUpdate(any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(StatusUpdateVerzender.class)) {
            assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() ->
                    statusUpdateEvent.fire(opdracht("https://omc.example.nl/callback"))));
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        verify(consumentCallbackAdapter).stuurStatusUpdate(any());
        Mockito.reset(consumentCallbackAdapter);
        assertEquals(1, fouten.size(), "de fout hoort op ERROR in het log te staan, anders is hij weg");
    }

    private static StatusUpdateOpdracht opdracht(String callbackUrl) {
        return new StatusUpdateOpdracht(UUID.randomUUID(), callbackUrl, 3L,
                NotificatieStatus.VERZONDEN, NotificatieStatus.BEZORGD, null, OffsetDateTime.parse("2026-01-15T10:00:00Z"));
    }
}
