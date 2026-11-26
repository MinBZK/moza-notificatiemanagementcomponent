package nl.rijksoverheid.moz.nmc.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import nl.rijksoverheid.moz.nmc.config.TaakConfig;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.taak.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.taak.TaakHandler;
import nl.rijksoverheid.moz.nmc.service.taak.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * Toetst de worker tegen de echte claimer en database, met een eigen worker die alleen de handler van
 * de test kent. Zo hangt de test niet af van welke handlers de applicatie zelf registreert.
 * {@code %test}: {@code nmc.taak.max-pogingen=2}.
 */
@QuarkusTest
class TaakWorkerTest {

    // @InjectSpy: zonder stubbing de echte claimer; de storingstests laten één methode gooien.
    @InjectSpy
    TaakClaimer taakClaimer;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    Clock clock;

    @Inject
    TaakConfig taakConfig;

    // JUnit maakt per test een nieuwe instantie van deze klasse, dus elke test begint met een verse
    // handler en een leeg register.
    private final TestTaakHandler handler = new TestTaakHandler();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private TaakWorker taakWorker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TestDatabase.leeg();
        Instance<TaakHandler> handlers = mock(Instance.class);
        when(handlers.iterator()).thenReturn(List.<TaakHandler>of(handler).iterator());
        taakWorker = new TaakWorker(taakClaimer, taakRepository, handlers, meterRegistry, clock, taakConfig);
    }

    @AfterEach
    void resetClaimer() {
        Mockito.reset(taakClaimer);
    }

    @Test
    void verwerk_periodiekeTaakUitgeput_blijftOpenEnWordtOpnieuwGepland() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetPeriodiek(true);
        handler.zetGedrag((taak, lease) -> {
            throw new IllegalStateException("database weg");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);
        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertTrue(taak.getDue().isAfter(nu()));
    }

    // Ook een uitstel dat als poging telt, leidt na het maximum tot uitputting; anders blijft een
    // handler die zijn eigen mislukking meldt eeuwig herhalen.
    @Test
    void verwerk_uitstelDatAlsPogingTelt_zetNaMaxPogingenOpMislukt() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> TaakUitkomst.uitgesteld(nu().minusSeconds(1), true));

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);
        assertEquals(1, zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow().getPogingen());
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(
                TaakStatus.MISLUKT,
                zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow().getStatus());
    }

    // Een handler die de uitputting afhandelt (de verzendtaak zet de notificatie op technisch-mislukt),
    // rondt de taak af in plaats van hem op mislukt te laten wachten.
    @Test
    void verwerk_uitputtingDoorDeHandlerAfgehandeld_rondtDeTaakAf() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });
        handler.zetBijUitputting(taak -> true);

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);
        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id).isEmpty());
    }

    // Gooit de handler bij het afhandelen van de uitputting, dan rolt die transactie terug: de taak is
    // niet afgerond en niet op mislukt gezet, en komt terug zodra zijn lease verloopt. De rest van de
    // batch gaat door.
    @Test
    void verwerk_afhandelenVanDeUitputtingFaalt_laatDeTaakStaanEnGaatDoorMetDeBatch() {
        UUID uitgeput = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(2));
        UUID volgende = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        QuarkusTransaction.requiringNew()
                .run(() -> TaakFixtures.zetPogingen(entityManager, TaakSoort.ONGELDIG_MELDEN, uitgeput, 1));
        handler.zetGedrag((taak, lease) -> {
            if (taak.getId().equals(uitgeput)) {
                throw new IllegalStateException("gesimuleerde fout");
            }

            return TaakUitkomst.afgerond();
        });
        handler.zetBijUitputting(taak -> {
            throw new IllegalStateException("afhandelen mislukt");
        });

        assertEquals(2, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, uitgeput).orElseThrow();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertEquals(1, taak.getPogingen(), "de mislukte afhandeling telt niet als poging");
        assertNotNull(taak.getLeaseTot(), "de lease blijft staan tot hij verloopt");
        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, volgende).isEmpty(), "de volgende taak is uitgevoerd");
    }

    @Test
    void verwerk_afgerond_verwijdertDeTaak() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        assertEquals(1, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));

        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id).isEmpty());
    }

    @Test
    void verwerk_uitgesteldZonderPoging_zetDueEnLaatPogingenStaan() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        OffsetDateTime straks = nu().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        handler.zetGedrag((taak, lease) -> TaakUitkomst.uitgesteld(straks, false));

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(straks.toInstant(), taak.getDue().toInstant());
        assertEquals(0, taak.getPogingen());
        assertNull(taak.getLeaseTot());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
    }

    // Een fout in de handler kost een poging en een uitstel; bij de tweede fout (max-pogingen=2) gaat
    // de taak op mislukt en telt hij in de metriek.
    @Test
    void verwerk_handlerGooit_steltUitEnZetNaMaxPogingenOpMislukt() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak naEerste = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(1, naEerste.getPogingen());
        assertEquals(TaakStatus.OPEN, naEerste.getStatus());
        assertTrue(naEerste.getDue().isAfter(nu().plusSeconds(20)), "uitstel met wachttijd");
        assertNull(naEerste.getLeaseTot());

        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak naTweede = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(TaakStatus.MISLUKT, naTweede.getStatus());
        assertEquals(
                1.0,
                meterRegistry
                        .get("nmc.taken.mislukt")
                        .tag("soort", "ONGELDIG_MELDEN")
                        .gauge()
                        .value());
        assertEquals(0, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN), "een mislukte taak wordt niet meer geclaimd");
    }

    @Test
    void verwerk_alAfgerondDoorDeHandler_laatDeRijMetRust() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() -> taakClaimer.rondAf(taak));

            return TaakUitkomst.alAfgerond();
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id).isEmpty());
    }

    @Test
    void verwerk_leaseVerlengen_zetDeLeaseVooruit() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            zetLease(id, nu().plusSeconds(1));
            lease.verleng();
            assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id)
                    .orElseThrow()
                    .getLeaseTot()
                    .isAfter(nu().plusMinutes(1)));

            return TaakUitkomst.uitgesteld(nu().plusHours(1), false);
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(
                0,
                zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow().getPogingen(),
                "de asserties in de handler zijn niet gegooid");
    }

    // Een andere worker heeft de taak inmiddels: wat deze worker nog wil, wordt geweigerd en de rij
    // blijft zoals de nieuwe eigenaar hem achterliet.
    @Test
    void verwerk_leaseVerloren_laatDeTaakAanDeNieuweEigenaar() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            QuarkusTransaction.requiringNew()
                    .run(() -> TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.ONGELDIG_MELDEN, id));

            return TaakUitkomst.afgerond();
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id).isPresent(), "de afronding van de oude worker is geweigerd");
    }

    // Terwijl de worker de eerste taak van de batch uitvoert, claimt een andere worker de tweede.
    @Test
    void verwerk_latereTaakInDeBatchGeclaimdDoorAndereWorker_slaatDieOver() {
        UUID eerste = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(2));
        UUID tweede = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        AtomicInteger uitgevoerd = new AtomicInteger();
        handler.zetGedrag((taak, lease) -> {
            uitgevoerd.incrementAndGet();
            QuarkusTransaction.requiringNew()
                    .run(() -> TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.ONGELDIG_MELDEN, tweede));

            return TaakUitkomst.afgerond();
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(1, uitgevoerd.get());
        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, eerste).isEmpty());
        assertEquals(0, zoek(TaakSoort.ONGELDIG_MELDEN, tweede).orElseThrow().getPogingen());
    }

    @Test
    void verwerk_leaseVerlorenNaEenFout_laatDeTaakAanDeNieuweEigenaar() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            QuarkusTransaction.requiringNew()
                    .run(() -> TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.ONGELDIG_MELDEN, id));

            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(
                0,
                zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow().getPogingen(),
                "het uitstel van de oude worker is geweigerd");
    }

    // Elke geplande ronde draait precies één ronde, voor haar eigen soort.
    @ParameterizedTest(name = "{0}")
    @MethodSource("geplandeRondes")
    void geplandeRonde_delegeertAanVerwerkVoorDeEigenSoort(TaakSoort soort, Consumer<TaakWorker> ronde) {
        TaakWorker spy = spy(taakWorker);
        doReturn(0).when(spy).verwerk(any());

        ronde.accept(spy);

        verify(spy).verwerk(soort);
        verify(spy, times(1)).verwerk(any());
    }

    // Een taaksoort zonder geplande ronde blijft eeuwig open staan.
    @Test
    void geplandeRondes_elkeTaakSoort_heeftEenRonde() {
        Set<TaakSoort> soorten = geplandeRondes()
                .map(argumenten -> (TaakSoort) argumenten.get()[0])
                .collect(Collectors.toSet());

        assertEquals(EnumSet.allOf(TaakSoort.class), soorten);
    }

    static Stream<Arguments> geplandeRondes() {
        return Stream.of(
                Arguments.of(TaakSoort.VERZENDEN, (Consumer<TaakWorker>) TaakWorker::verzenden),
                Arguments.of(TaakSoort.RECEIPT_VERWERKEN, (Consumer<TaakWorker>) TaakWorker::receiptVerwerken),
                Arguments.of(TaakSoort.RECONCILIEREN, (Consumer<TaakWorker>) TaakWorker::reconcilieren),
                Arguments.of(TaakSoort.BEZORGING_VASTSTELLEN, (Consumer<TaakWorker>) TaakWorker::bezorgingVaststellen),
                Arguments.of(TaakSoort.TERUGKOPPELEN, (Consumer<TaakWorker>) TaakWorker::terugkoppelen),
                Arguments.of(TaakSoort.ONGELDIG_MELDEN, (Consumer<TaakWorker>) TaakWorker::ongeldigMelden),
                Arguments.of(TaakSoort.WISSEN, (Consumer<TaakWorker>) TaakWorker::wissen),
                Arguments.of(TaakSoort.CONTROLE, (Consumer<TaakWorker>) TaakWorker::controle),
                Arguments.of(TaakSoort.ONDERHOUD, (Consumer<TaakWorker>) TaakWorker::onderhoud));
    }

    @Test
    void ongeldigMelden_taakAanDeBeurt_voertDeHandlerUit() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        taakWorker.ongeldigMelden();

        assertTrue(zoek(TaakSoort.ONGELDIG_MELDEN, id).isEmpty());
    }

    @Test
    void achterstandmetriek_takenNuEnLater_teltAlleenWatAanDeBeurtIs() {
        plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        plan(TaakSoort.ONGELDIG_MELDEN, nu().plusHours(1));

        assertEquals(
                1.0,
                meterRegistry
                        .get("nmc.taken.achterstand")
                        .tag("soort", "ONGELDIG_MELDEN")
                        .gauge()
                        .value());
    }

    // Het aantal aanroepen van de handler is het aantal geclaimde taken; de teller bewijst dat de
    // worker elke taak precies één keer aanbiedt.
    @Test
    void verwerk_meerdereTaken_biedtElkeTaakEenKeerAan() {
        for (int i = 0; i < 3; i++) {
            plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        }
        AtomicInteger aanroepen = new AtomicInteger();
        handler.zetGedrag((taak, lease) -> {
            aanroepen.incrementAndGet();

            return TaakUitkomst.afgerond();
        });

        assertEquals(3, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));
        assertEquals(3, aanroepen.get());
        assertEquals(0, taakRepository.count());
    }

    // De handler heeft nog niet gedraaid als het verlengen faalt; dat kost dus geen poging.
    @Test
    void verwerk_verlengenVoorDeHandlerFaalt_slaatDeTaakOverZonderPoging() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        AtomicInteger aanroepen = new AtomicInteger();
        handler.zetGedrag((taak, lease) -> {
            aanroepen.incrementAndGet();

            return TaakUitkomst.afgerond();
        });
        doThrow(new IllegalStateException("gesimuleerde databasefout"))
                .when(taakClaimer)
                .verleng(any());

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(0, aanroepen.get());
        assertEquals(0, taak.getPogingen());
        assertNotNull(taak.getLeaseTot(), "de taak komt terug zodra zijn lease verloopt");
    }

    // De handler deed zijn werk; een fout bij het afronden kost geen poging. De taak komt terug zodra
    // zijn lease verloopt en de handler draait dan opnieuw.
    @Test
    void verwerk_afrondenFaalt_kostGeenPoging() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        doThrow(new IllegalStateException("gesimuleerde databasefout"))
                .when(taakClaimer)
                .rondAf(any());

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(0, taak.getPogingen());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertNotNull(taak.getLeaseTot(), "de taak komt terug zodra zijn lease verloopt");
    }

    // Een storing bij het uitstellen na een fout kost geen poging; de taak komt terug zodra zijn lease verloopt.
    @Test
    void verwerk_uitstellenNaEenFoutFaalt_laatDeTaakStaanTotDeLeaseVerloopt() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        handler.zetGedrag((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });
        doThrow(new IllegalStateException("gesimuleerde databasefout"))
                .when(taakClaimer)
                .stelUit(any(), any(), anyBoolean());

        assertEquals(1, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(0, taak.getPogingen());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertNotNull(taak.getLeaseTot());
    }

    // Een andere worker claimde de taak voordat deze hem na de laatste poging op mislukt kon zetten.
    @Test
    void verwerk_leaseVerlorenVoorDeUitputting_zetDeTaakNietOpMislukt() {
        UUID id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        QuarkusTransaction.requiringNew()
                .run(() -> TaakFixtures.zetPogingen(entityManager, TaakSoort.ONGELDIG_MELDEN, id, 1));
        handler.zetGedrag((taak, lease) -> {
            QuarkusTransaction.requiringNew()
                    .run(() -> TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.ONGELDIG_MELDEN, id));

            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(TaakSoort.ONGELDIG_MELDEN, id).orElseThrow();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertEquals(1, taak.getPogingen());
    }

    // Binnen de verversingstermijn bedient één telling elke scrape; een nieuwe taak telt pas na de termijn.
    @Test
    void achterstandmetriek_binnenDeVerversingstermijn_hergebruiktDeTelling() {
        TaakConfig config = TestConfig.taak();
        when(config.metriekVerversing()).thenReturn(Duration.ofHours(1));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new TaakWorker(taakClaimer, taakRepository, geenHandlers(), registry, Clock.systemUTC(), config);
        Gauge achterstand = registry.get("nmc.taken.achterstand")
                .tag("soort", "ONGELDIG_MELDEN")
                .gauge();

        assertEquals(0.0, achterstand.value());
        plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        assertEquals(0.0, achterstand.value());
    }

    // %test zet de verversing op nul: elke scrape na de eerste telt opnieuw.
    @Test
    void achterstandmetriek_naDeVerversingstermijn_teltOpnieuw() {
        Gauge achterstand = meterRegistry
                .get("nmc.taken.achterstand")
                .tag("soort", "ONGELDIG_MELDEN")
                .gauge();

        assertEquals(0.0, achterstand.value());
        plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        assertEquals(1.0, achterstand.value());
    }

    @SuppressWarnings("unchecked")
    private static Instance<TaakHandler> geenHandlers() {
        Instance<TaakHandler> beans = mock(Instance.class);
        when(beans.iterator()).thenReturn(Collections.emptyIterator());

        return beans;
    }

    // Een storing bij het claimen is één waarschuwing, geen exceptie uit de geplande ronde.
    @Test
    void verwerk_claimFaalt_geeftNulTerug() {
        doThrow(new IllegalStateException("gesimuleerde databasefout"))
                .when(taakClaimer)
                .claim(any(), anyInt());

        assertEquals(0, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));
    }

    private UUID plan(TaakSoort taakSoort, OffsetDateTime due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(taakSoort, NotificatieFixtures.DV_ID, null, due, null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });
    }

    private java.util.Optional<Taak> zoek(TaakSoort taakSoort, UUID id) {
        return LeesHelper.taak(taakSoort, id);
    }

    private void zetDue(UUID id, OffsetDateTime due) {
        QuarkusTransaction.requiringNew()
                .run(() -> TaakFixtures.zetDue(entityManager, TaakSoort.ONGELDIG_MELDEN, id, due));
    }

    private void zetLease(UUID id, OffsetDateTime tot) {
        QuarkusTransaction.requiringNew()
                .run(() -> TaakFixtures.zetLeaseTot(entityManager, TaakSoort.ONGELDIG_MELDEN, id, tot));
    }

    private static OffsetDateTime nu() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
