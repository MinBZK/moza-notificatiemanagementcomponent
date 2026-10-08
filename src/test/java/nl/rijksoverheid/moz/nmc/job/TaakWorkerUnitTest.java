package nl.rijksoverheid.moz.nmc.job;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.TaakHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Zonder Quarkus: in de applicatie heeft inmiddels elke soort een handler.
class TaakWorkerUnitTest {

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

    @SuppressWarnings("unchecked")
    private TaakWorker worker(TaakHandler... handlers) {
        Instance<TaakHandler> beans = mock(Instance.class);
        when(beans.iterator()).thenReturn(List.of(handlers).iterator());

        return new TaakWorker(taakClaimer, mock(TaakRepository.class), beans, new SimpleMeterRegistry(), 10, 2);
    }

    private static TaakHandler handler(TaakSoort soort) {
        TaakHandler handler = mock(TaakHandler.class);
        when(handler.soort()).thenReturn(soort);

        return handler;
    }
}
