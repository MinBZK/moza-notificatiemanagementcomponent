package nl.rijksoverheid.moz.nmc.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.TaakHandler;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Zonder Quarkus: in de applicatie heeft inmiddels elke soort een handler.
class TaakWorkerUnitTest {

    private static final Instant NU = Instant.parse("2030-01-01T10:00:00Z");

    private final TaakClaimer taakClaimer = mock(TaakClaimer.class);

    @Test
    void verwerk_zonderHandlerVoorDeSoort_claimtNiets() {
        TaakWorker worker = worker(handler(TaakSoort.CONTROLE));

        assertEquals(0, worker.verwerk(TaakSoort.ONDERHOUD));

        verifyNoInteractions(taakClaimer);
    }

    @Test
    void constructor_tweeHandlersVoorEenSoort_weigert() {
        TaakHandler eerste = handler(TaakSoort.WISSEN);
        TaakHandler tweede = handler(TaakSoort.WISSEN);

        IllegalStateException fout = assertThrows(IllegalStateException.class, () -> worker(eerste, tweede));

        assertTrue(fout.getMessage().contains("Twee handlers voor taaksoort WISSEN"), fout.getMessage());
    }

    // De wachttijd na een fout verdubbelt per poging vanaf 30 seconden en houdt op bij een dag.
    @ParameterizedTest
    @CsvSource({"0, 30", "1, 60", "11, 61440", "12, 86400", "40, 86400"})
    void verwerk_handlerGooit_steltUitMetOplopendeWachttijdTotEenDag(int eerderePogingen, long wachttijdSeconden) {
        TaakHandler handler = handler(TaakSoort.WISSEN);
        when(handler.voerUit(any(), any())).thenThrow(new IllegalStateException("gesimuleerde fout"));
        Taak taak = mock(Taak.class);
        when(taak.getPogingen()).thenReturn(eerderePogingen);
        when(taakClaimer.claim(TaakSoort.WISSEN, 10)).thenReturn(List.of(taak));
        TaakConfig config = TestConfig.taak();
        when(config.maxPogingen()).thenReturn(100);

        assertEquals(1, worker(config, handler).verwerk(TaakSoort.WISSEN));

        verify(taakClaimer).stelUit(taak, OffsetDateTime.ofInstant(NU, ZoneOffset.UTC).plus(Duration.ofSeconds(wachttijdSeconden)), true);
    }

    private TaakWorker worker(TaakHandler... handlers) {
        return worker(TestConfig.taak(), handlers);
    }

    @SuppressWarnings("unchecked")
    private TaakWorker worker(TaakConfig config, TaakHandler... handlers) {
        Instance<TaakHandler> beans = mock(Instance.class);
        when(beans.iterator()).thenReturn(List.of(handlers).iterator());

        return new TaakWorker(taakClaimer, mock(TaakRepository.class), beans, new SimpleMeterRegistry(),
                Clock.fixed(NU, ZoneOffset.UTC), config);
    }

    private static TaakHandler handler(TaakSoort soort) {
        TaakHandler handler = mock(TaakHandler.class);
        when(handler.taakSoort()).thenReturn(soort);

        return handler;
    }
}
