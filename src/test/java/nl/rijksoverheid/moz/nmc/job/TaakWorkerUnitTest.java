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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Zonder Quarkus: in de applicatie heeft inmiddels elke soort een handler.
class TaakWorkerUnitTest {

    private static final OffsetDateTime NU = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock VASTE_KLOK = Clock.fixed(Instant.from(NU), ZoneOffset.UTC);

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

    // De wachttijd begint op 30 seconden en verdubbelt per mislukte poging, tot ten hoogste een dag.
    // Poging 12 is de laatste onder die grens, poging 13 de eerste erboven. Poging 60 zou zonder grens
    // op de verdubbeling de Duration laten overlopen, en vanaf poging 65 begint de verdubbeling opnieuw.
    @ParameterizedTest
    @CsvSource({
            "1, 30",
            "2, 60",
            "3, 120",
            "4, 240",
            "12, 61440",
            "13, 86400",
            "22, 86400",
            "60, 86400",
            "65, 86400",
            "99, 86400"
    })
    void verwerk_handlerFaalt_steltUitMetEenWachttijdDieVerdubbeltTotEenDag(int poging, long verwachteSeconden) {
        TaakHandler handler = handler(TaakSoort.WISSEN);
        when(handler.voerUit(any(), any())).thenThrow(new IllegalStateException("gesimuleerde fout"));
        Taak taak = taakMetPogingen(TaakSoort.WISSEN, poging - 1);
        TaakWorker worker = worker(VASTE_KLOK, TestConfig.taak(100), handler);

        worker.verwerk(TaakSoort.WISSEN);

        verify(taakClaimer).stelUit(taak, NU.plusSeconds(verwachteSeconden), true);
    }

    // Een periodieke taak gaat na het maximum niet op mislukt: hij wacht de wachttijd van de laatste
    // poging, zonder dat het uitstel als poging telt.
    @Test
    void verwerk_periodiekeTaakUitgeput_steltUitMetDeWachttijdVanHetMaximum() {
        TaakHandler handler = handler(TaakSoort.CONTROLE);
        when(handler.periodiek()).thenReturn(true);
        when(handler.voerUit(any(), any())).thenThrow(new IllegalStateException("gesimuleerde fout"));
        Taak taak = taakMetPogingen(TaakSoort.CONTROLE, 3);
        TaakWorker worker = worker(VASTE_KLOK, TestConfig.taak(4), handler);

        worker.verwerk(TaakSoort.CONTROLE);

        verify(taakClaimer).stelUit(taak, NU.plusSeconds(240), false);
    }

    // De claim levert deze ene taak, die al een aantal keer mislukt is.
    private Taak taakMetPogingen(TaakSoort soort, int pogingen) {
        Taak taak = mock(Taak.class);
        when(taak.getTaakSoort()).thenReturn(soort);
        when(taak.getId()).thenReturn(UUID.randomUUID());
        when(taak.getPogingen()).thenReturn(pogingen);
        when(taakClaimer.claim(eq(soort), anyInt())).thenReturn(List.of(taak));

        return taak;
    }

    private TaakWorker worker(TaakHandler... handlers) {
        return worker(Clock.systemUTC(), TestConfig.taak(), handlers);
    }

    @SuppressWarnings("unchecked")
    private TaakWorker worker(Clock clock, TaakConfig config, TaakHandler... handlers) {
        Instance<TaakHandler> beans = mock(Instance.class);
        when(beans.iterator()).thenReturn(List.of(handlers).iterator());

        return new TaakWorker(taakClaimer, mock(TaakRepository.class), beans, new SimpleMeterRegistry(), clock, config);
    }

    private static TaakHandler handler(TaakSoort soort) {
        TaakHandler handler = mock(TaakHandler.class);
        when(handler.taakSoort()).thenReturn(soort);

        return handler;
    }
}
