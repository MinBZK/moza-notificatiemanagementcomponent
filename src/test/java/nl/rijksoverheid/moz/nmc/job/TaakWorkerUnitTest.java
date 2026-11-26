package nl.rijksoverheid.moz.nmc.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.taak.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.taak.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.taak.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

// Zonder Quarkus: in de applicatie heeft inmiddels elke soort een handler.
@Tag("unit")
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

    // Een taak die faalt mag de rest van de batch niet tegenhouden.
    @Test
    void verwerk_eersteTaakFaalt_voertDeTweedeNogUit() {
        TaakHandler handler = handler(TaakSoort.WISSEN);
        Taak eerste = taak(TaakSoort.WISSEN);
        Taak tweede = taak(TaakSoort.WISSEN);
        when(taakClaimer.claim(eq(TaakSoort.WISSEN), anyInt())).thenReturn(List.of(eerste, tweede));
        when(handler.voerUit(eq(eerste), any())).thenThrow(new IllegalStateException("gesimuleerde fout"));
        when(handler.voerUit(eq(tweede), any())).thenReturn(TaakUitkomst.afgerond());
        TaakWorker worker = worker(VASTE_KLOK, TestConfig.taak(), handler);

        assertEquals(2, worker.verwerk(TaakSoort.WISSEN));

        verify(taakClaimer).stelUit(eerste, NU.plusSeconds(30), true);
        verify(taakClaimer).rondAf(tweede);
    }

    // Ook niet als het bijwerken van de mislukte taak zelf faalt, zoals bij een databasestoring.
    @Test
    void verwerk_uitstelVanDeEersteTaakFaalt_voertDeTweedeNogUit() {
        TaakHandler handler = handler(TaakSoort.WISSEN);
        Taak eerste = taak(TaakSoort.WISSEN);
        Taak tweede = taak(TaakSoort.WISSEN);
        when(taakClaimer.claim(eq(TaakSoort.WISSEN), anyInt())).thenReturn(List.of(eerste, tweede));
        when(handler.voerUit(eq(eerste), any())).thenThrow(new IllegalStateException("gesimuleerde fout"));
        when(handler.voerUit(eq(tweede), any())).thenReturn(TaakUitkomst.afgerond());
        doThrow(new IllegalStateException("database weg")).when(taakClaimer).stelUit(eq(eerste), any(), anyBoolean());
        TaakWorker worker = worker(VASTE_KLOK, TestConfig.taak(), handler);

        assertEquals(2, worker.verwerk(TaakSoort.WISSEN));

        verify(handler).voerUit(eq(tweede), any());
        verify(taakClaimer).rondAf(tweede);
    }

    static Stream<Arguments> geplandeMethodes() {
        return Stream.of(
                arguments(TaakSoort.VERZENDEN, (Consumer<TaakWorker>) TaakWorker::verzenden),
                arguments(TaakSoort.RECEIPT_VERWERKEN, (Consumer<TaakWorker>) TaakWorker::receiptVerwerken),
                arguments(TaakSoort.RECONCILIEREN, (Consumer<TaakWorker>) TaakWorker::reconcilieren),
                arguments(TaakSoort.BEZORGING_VASTSTELLEN, (Consumer<TaakWorker>) TaakWorker::bezorgingVaststellen),
                arguments(TaakSoort.TERUGKOPPELEN, (Consumer<TaakWorker>) TaakWorker::terugkoppelen),
                arguments(TaakSoort.ONGELDIG_MELDEN, (Consumer<TaakWorker>) TaakWorker::ongeldigMelden),
                arguments(TaakSoort.WISSEN, (Consumer<TaakWorker>) TaakWorker::wissen),
                arguments(TaakSoort.CONTROLE, (Consumer<TaakWorker>) TaakWorker::controle),
                arguments(TaakSoort.ONDERHOUD, (Consumer<TaakWorker>) TaakWorker::onderhoud));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("geplandeMethodes")
    void geplandeMethode_claimtAlleenDeEigenSoort(TaakSoort soort, Consumer<TaakWorker> geplandeMethode) {
        TaakWorker worker = workerMetElkeHandler();

        geplandeMethode.accept(worker);

        verify(taakClaimer).claim(eq(soort), anyInt());
        verifyNoMoreInteractions(taakClaimer);
    }

    // De scheduler staat uit in tests. Een methode zonder @Scheduled draait in productie nooit, en een
    // nieuwe taaksoort zonder geplande methode wordt nooit geclaimd.
    @Test
    void elkeTaaksoort_heeftPreciesEenGeplandeMethode() throws ReflectiveOperationException {
        TaakWorker worker = workerMetElkeHandler();
        List<Method> gepland = Arrays.stream(TaakWorker.class.getDeclaredMethods())
                .filter(methode -> methode.isAnnotationPresent(Scheduled.class))
                .toList();

        for (Method methode : gepland) {
            methode.invoke(worker);
        }

        ArgumentCaptor<TaakSoort> geclaimd = ArgumentCaptor.forClass(TaakSoort.class);
        verify(taakClaimer, times(gepland.size())).claim(geclaimd.capture(), anyInt());
        assertEquals(TaakSoort.values().length, gepland.size());
        assertEquals(EnumSet.allOf(TaakSoort.class), EnumSet.copyOf(geclaimd.getAllValues()));
    }

    // Zonder handler voor een soort claimt de worker die soort niet.
    private TaakWorker workerMetElkeHandler() {
        return worker(Arrays.stream(TaakSoort.values())
                .map(TaakWorkerUnitTest::handler)
                .toArray(TaakHandler[]::new));
    }

    // De claim levert deze ene taak, die al een aantal keer mislukt is.
    private Taak taakMetPogingen(TaakSoort soort, int pogingen) {
        Taak taak = taak(soort);
        when(taak.getPogingen()).thenReturn(pogingen);
        when(taakClaimer.claim(eq(soort), anyInt())).thenReturn(List.of(taak));

        return taak;
    }

    private static Taak taak(TaakSoort soort) {
        Taak taak = mock(Taak.class);
        when(taak.getTaakSoort()).thenReturn(soort);
        when(taak.getId()).thenReturn(UUID.randomUUID());

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
