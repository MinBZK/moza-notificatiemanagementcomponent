package nl.rijksoverheid.moz.nmc.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

// %test: nmc.taak.max-pogingen=2, de lus zelf staat uit.
@QuarkusTest
class TaakWorkerTest {

    @Inject
    TaakWorker taakWorker;

    // @InjectSpy: zonder stubbing de echte claimer; de storingstests laten één methode gooien.
    @InjectSpy
    TaakClaimer taakClaimer;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            TaakFixtures.leegVerzendbudget(entityManager);
        });
    }

    @AfterEach
    void resetHandler() {
        Mockito.reset(taakClaimer);
        ControleTestHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.afgerond());
        ControleTestHandler.BIJ_UITPUTTING.set(taak -> false);
        ControleTestHandler.PERIODIEK.set(false);
    }

    @Test
    void verwerk_periodiekeTaakUitgeput_blijftOpenEnWordtOpnieuwGepland() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.PERIODIEK.set(true);
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            throw new IllegalStateException("database weg");
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);
        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak taak = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertTrue(taak.getDue().isAfter(nu()));
    }

    // Ook een uitstel dat als poging telt, leidt na het maximum tot uitputting; anders blijft een
    // handler die zijn eigen mislukking meldt eeuwig herhalen.
    @Test
    void verwerk_uitstelDatAlsPogingTelt_zetNaMaxPogingenOpMislukt() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.uitgesteld(nu().minusSeconds(1), true));

        taakWorker.verwerk(TaakSoort.CONTROLE);
        assertEquals(1, zoek(TaakSoort.CONTROLE, id).orElseThrow().getPogingen());
        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(TaakStatus.MISLUKT, zoek(TaakSoort.CONTROLE, id).orElseThrow().getStatus());
    }

    // Een handler die de uitputting afhandelt (de verzendtaak zet de notificatie op technisch-mislukt),
    // rondt de taak af in plaats van hem op mislukt te laten wachten.
    @Test
    void verwerk_uitputtingDoorDeHandlerAfgehandeld_rondtDeTaakAf() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });
        ControleTestHandler.BIJ_UITPUTTING.set(taak -> true);

        taakWorker.verwerk(TaakSoort.CONTROLE);
        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertTrue(zoek(TaakSoort.CONTROLE, id).isEmpty());
    }

    @Test
    void verwerk_afgerond_verwijdertDeTaak() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));

        assertEquals(1, taakWorker.verwerk(TaakSoort.CONTROLE));

        assertTrue(zoek(TaakSoort.CONTROLE, id).isEmpty());
    }

    @Test
    void verwerk_uitgesteldZonderPoging_zetDueEnLaatPogingenStaan() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        OffsetDateTime straks = nu().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ControleTestHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.uitgesteld(straks, false));

        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak taak = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(straks.toInstant(), taak.getDue().toInstant());
        assertEquals(0, taak.getPogingen());
        assertNull(taak.getLeaseTot());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
    }

    // Een fout in de handler kost een poging en een uitstel; bij de tweede fout (max-pogingen=2) gaat
    // de taak op mislukt en telt hij in de metriek.
    @Test
    void verwerk_handlerGooit_steltUitEnZetNaMaxPogingenOpMislukt() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak naEerste = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(1, naEerste.getPogingen());
        assertEquals(TaakStatus.OPEN, naEerste.getStatus());
        assertTrue(naEerste.getDue().isAfter(nu().plusSeconds(20)), "uitstel met wachttijd");
        assertNull(naEerste.getLeaseTot());

        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak naTweede = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(TaakStatus.MISLUKT, naTweede.getStatus());
        assertEquals(1.0, meterRegistry.get("nmc.taken.mislukt").tag("soort", "CONTROLE").gauge().value());
        assertEquals(0, taakWorker.verwerk(TaakSoort.CONTROLE), "een mislukte taak wordt niet meer geclaimd");
    }

    @Test
    void verwerk_alAfgerondDoorDeHandler_laatDeRijMetRust() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() -> taakClaimer.rondAf(taak));

            return TaakUitkomst.alAfgerond();
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertTrue(zoek(TaakSoort.CONTROLE, id).isEmpty());
    }

    @Test
    void verwerk_leaseVerlengen_zetDeLeaseVooruit() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            zetLease(id, nu().plusSeconds(1));
            lease.verleng();
            assertTrue(zoek(TaakSoort.CONTROLE, id).orElseThrow().getLeaseTot().isAfter(nu().plusMinutes(1)));

            return TaakUitkomst.uitgesteld(nu().plusHours(1), false);
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(0, zoek(TaakSoort.CONTROLE, id).orElseThrow().getPogingen(), "de asserties in de handler zijn niet gegooid");
    }

    // Een andere worker heeft de taak inmiddels: wat deze worker nog wil, wordt geweigerd en de rij
    // blijft zoals de nieuwe eigenaar hem achterliet.
    @Test
    void verwerk_leaseVerloren_laatDeTaakAanDeNieuweEigenaar() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() ->
                    TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.CONTROLE, id));

            return TaakUitkomst.afgerond();
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertTrue(zoek(TaakSoort.CONTROLE, id).isPresent(), "de afronding van de oude worker is geweigerd");
    }

    // Terwijl de worker de eerste taak van de batch uitvoert, claimt een andere worker de tweede.
    @Test
    void verwerk_latereTaakInDeBatchGeclaimdDoorAndereWorker_slaatDieOver() {
        long eerste = plan(TaakSoort.CONTROLE, nu().minusMinutes(2));
        long tweede = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        AtomicInteger uitgevoerd = new AtomicInteger();
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            uitgevoerd.incrementAndGet();
            QuarkusTransaction.requiringNew().run(() ->
                    TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.CONTROLE, tweede));

            return TaakUitkomst.afgerond();
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(1, uitgevoerd.get());
        assertTrue(zoek(TaakSoort.CONTROLE, eerste).isEmpty());
        assertEquals(0, zoek(TaakSoort.CONTROLE, tweede).orElseThrow().getPogingen());
    }

    @Test
    void verwerk_leaseVerlorenNaEenFout_laatDeTaakAanDeNieuweEigenaar() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() ->
                    TaakFixtures.claimDoorAndereWorker(entityManager, TaakSoort.CONTROLE, id));

            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(0, zoek(TaakSoort.CONTROLE, id).orElseThrow().getPogingen(), "het uitstel van de oude worker is geweigerd");
    }

    @Test
    void verwerk_zonderHandlerVoorDeSoort_claimtNiets() {
        long id = plan(TaakSoort.WISSEN, nu().minusMinutes(1));

        assertEquals(0, taakWorker.verwerk(TaakSoort.WISSEN));

        assertNull(zoek(TaakSoort.WISSEN, id).orElseThrow().getLeaseTot());
    }

    // De negen geplande rondes delegeren elk aan verwerk; zonder handler doen ze niets.
    @Test
    void geplandeRondes_zonderHandler_latenTakenStaan() {
        long id = plan(TaakSoort.VERZENDEN, nu().minusMinutes(1));

        taakWorker.verzenden();
        taakWorker.receiptVerwerken();
        taakWorker.reconcilieren();
        taakWorker.bezorgingVaststellen();
        taakWorker.terugkoppelen();
        taakWorker.ongeldigMelden();
        taakWorker.wissen();
        taakWorker.onderhoud();

        assertNull(zoek(TaakSoort.VERZENDEN, id).orElseThrow().getLeaseTot());
    }

    @Test
    void controle_taakAanDeBeurt_voertDeHandlerUit() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));

        taakWorker.controle();

        assertTrue(zoek(TaakSoort.CONTROLE, id).isEmpty());
    }

    @Test
    void achterstandmetriek_takenNuEnLater_teltAlleenWatAanDeBeurtIs() {
        plan(TaakSoort.WISSEN, nu().minusMinutes(1));
        plan(TaakSoort.WISSEN, nu().plusHours(1));

        assertEquals(1.0, meterRegistry.get("nmc.taken.achterstand").tag("soort", "WISSEN").gauge().value());
    }

    // Het aantal aanroepen van de handler is het aantal geclaimde taken; de teller bewijst dat de
    // worker elke taak precies één keer aanbiedt.
    @Test
    void verwerk_meerdereTaken_biedtElkeTaakEenKeerAan() {
        for (int i = 0; i < 3; i++) {
            plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        }
        AtomicInteger aanroepen = new AtomicInteger();
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            aanroepen.incrementAndGet();

            return TaakUitkomst.afgerond();
        });

        assertEquals(3, taakWorker.verwerk(TaakSoort.CONTROLE));
        assertEquals(3, aanroepen.get());
        assertEquals(0, taakRepository.count());
    }

    // De handler heeft nog niet gedraaid als het verlengen faalt; dat kost dus geen poging.
    @Test
    void verwerk_verlengenVoorDeHandlerFaalt_slaatDeTaakOverZonderPoging() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        AtomicInteger aanroepen = new AtomicInteger();
        ControleTestHandler.GEDRAG.set((taak, lease) -> {
            aanroepen.incrementAndGet();

            return TaakUitkomst.afgerond();
        });
        doThrow(new IllegalStateException("gesimuleerde databasefout")).when(taakClaimer).verleng(any());

        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak taak = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(0, aanroepen.get());
        assertEquals(0, taak.getPogingen());
        assertNotNull(taak.getLeaseTot(), "de taak komt terug zodra zijn lease verloopt");
    }

    // De handler deed zijn werk; een fout bij het afronden kost geen poging. De taak komt terug zodra
    // zijn lease verloopt en de handler draait dan opnieuw.
    @Test
    void verwerk_afrondenFaalt_kostGeenPoging() {
        long id = plan(TaakSoort.CONTROLE, nu().minusMinutes(1));
        doThrow(new IllegalStateException("gesimuleerde databasefout")).when(taakClaimer).rondAf(any());

        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak taak = zoek(TaakSoort.CONTROLE, id).orElseThrow();
        assertEquals(0, taak.getPogingen());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertNotNull(taak.getLeaseTot(), "de taak komt terug zodra zijn lease verloopt");
    }

    // Een storing bij het claimen is één waarschuwing, geen exceptie uit de geplande ronde.
    @Test
    void verwerk_claimFaalt_geeftNulTerug() {
        doThrow(new IllegalStateException("gesimuleerde databasefout")).when(taakClaimer).claim(any(), anyInt());

        assertEquals(0, taakWorker.verwerk(TaakSoort.CONTROLE));
    }

    private long plan(TaakSoort taakSoort, OffsetDateTime due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(taakSoort, NotificatieFixtures.DV_ID, null, due, null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });
    }

    private java.util.Optional<Taak> zoek(TaakSoort taakSoort, long id) {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.zoek(taakSoort, id));
    }

    private void zetDue(long id, OffsetDateTime due) {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetDue(entityManager, TaakSoort.CONTROLE, id, due));
    }

    private void zetLease(long id, OffsetDateTime tot) {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetLeaseTot(entityManager, TaakSoort.CONTROLE, id, tot));
    }

    private static OffsetDateTime nu() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
