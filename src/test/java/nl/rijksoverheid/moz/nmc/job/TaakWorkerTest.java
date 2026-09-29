package nl.rijksoverheid.moz.nmc.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.TaakClaimer;
import nl.rijksoverheid.moz.nmc.service.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: nmc.taak.max-pogingen=2, de lus zelf staat uit.
@QuarkusTest
class TaakWorkerTest {

    @Inject
    TaakWorker taakWorker;

    @Inject
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
            entityManager.createNativeQuery("DELETE FROM verzendbudget").executeUpdate();
        });
    }

    @AfterEach
    void resetHandler() {
        TestTaakHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.afgerond());
        TestTaakHandler.BIJ_UITPUTTING.set(taak -> false);
    }

    // Ook een uitstel dat als poging telt, leidt na het maximum tot uitputting; anders blijft een
    // handler die zijn eigen mislukking meldt eeuwig herhalen.
    @Test
    void verwerk_uitstelDatAlsPogingTelt_zetNaMaxPogingenOpMislukt() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.uitgesteld(nu().minusSeconds(1), true));

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);
        assertEquals(1, zoek(id).orElseThrow().getPogingen());
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(TaakStatus.MISLUKT, zoek(id).orElseThrow().getStatus());
    }

    // Een handler die de uitputting afhandelt (de verzendtaak zet de notificatie op technisch-mislukt),
    // rondt de taak af in plaats van hem op mislukt te laten wachten.
    @Test
    void verwerk_uitputtingDoorDeHandlerAfgehandeld_rondtDeTaakAf() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });
        TestTaakHandler.BIJ_UITPUTTING.set(taak -> true);

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);
        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(id).isEmpty());
    }

    @Test
    void verwerk_afgerond_verwijdertDeTaak() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        assertEquals(1, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));

        assertTrue(zoek(id).isEmpty());
    }

    @Test
    void verwerk_uitgesteldZonderPoging_zetDueEnLaatPogingenStaan() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        OffsetDateTime straks = nu().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        TestTaakHandler.GEDRAG.set((taak, lease) -> TaakUitkomst.uitgesteld(straks, false));

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak taak = zoek(id).orElseThrow();
        assertEquals(straks.toInstant(), taak.getDue().toInstant());
        assertEquals(0, taak.getPogingen());
        assertNull(taak.getLeaseTot());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
    }

    // Een fout in de handler kost een poging en een uitstel; bij de tweede fout (max-pogingen=2) gaat
    // de taak op mislukt en telt hij in de metriek.
    @Test
    void verwerk_handlerGooit_steltUitEnZetNaMaxPogingenOpMislukt() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak naEerste = zoek(id).orElseThrow();
        assertEquals(1, naEerste.getPogingen());
        assertEquals(TaakStatus.OPEN, naEerste.getStatus());
        assertTrue(naEerste.getDue().isAfter(nu().plusSeconds(20)), "uitstel met wachttijd");
        assertNull(naEerste.getLeaseTot());

        zetDue(id, nu().minusSeconds(1));
        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        Taak naTweede = zoek(id).orElseThrow();
        assertEquals(TaakStatus.MISLUKT, naTweede.getStatus());
        assertEquals(1.0, meterRegistry.get("nmc.taken.mislukt").tag("soort", "ONGELDIG_MELDEN").gauge().value());
        assertEquals(0, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN), "een mislukte taak wordt niet meer geclaimd");
    }

    @Test
    void verwerk_alAfgerondDoorDeHandler_laatDeRijMetRust() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() -> taakClaimer.rondAf(taak));

            return TaakUitkomst.alAfgerond();
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(id).isEmpty());
    }

    @Test
    void verwerk_leaseVerlengen_zetDeLeaseVooruit() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            zetLease(id, nu().plusSeconds(1));
            lease.verleng();
            assertTrue(zoek(id).orElseThrow().getLeaseTot().isAfter(nu().plusMinutes(1)));

            return TaakUitkomst.uitgesteld(nu().plusHours(1), false);
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(0, zoek(id).orElseThrow().getPogingen(), "de asserties in de handler zijn niet gegooid");
    }

    // Een andere worker heeft de taak inmiddels: wat deze worker nog wil, wordt geweigerd en de rij
    // blijft zoals de nieuwe eigenaar hem achterliet.
    @Test
    void verwerk_leaseVerloren_laatDeTaakAanDeNieuweEigenaar() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("UPDATE taak SET claim_epoch = claim_epoch + 1 WHERE id = ?1")
                    .setParameter(1, id).executeUpdate());

            return TaakUitkomst.afgerond();
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertTrue(zoek(id).isPresent(), "de afronding van de oude worker is geweigerd");
    }

    @Test
    void verwerk_leaseVerlorenNaEenFout_laatDeTaakAanDeNieuweEigenaar() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("UPDATE taak SET claim_epoch = claim_epoch + 1 WHERE id = ?1")
                    .setParameter(1, id).executeUpdate());

            throw new IllegalStateException("gesimuleerde fout");
        });

        taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN);

        assertEquals(0, zoek(id).orElseThrow().getPogingen(), "het uitstel van de oude worker is geweigerd");
    }

    // De geplande rondes delegeren elk aan verwerk voor hun eigen soort; zonder werk van die soort
    // blijft een taak van een andere soort onaangeroerd.
    @Test
    void geplandeRondes_andereSoort_latenDeTaakStaan() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        taakWorker.receiptVerwerken();
        taakWorker.reconcilieren();
        taakWorker.bezorgingVaststellen();
        taakWorker.terugkoppelen();
        taakWorker.wissen();
        taakWorker.onderhoud();

        assertNull(zoek(id).orElseThrow().getLeaseTot());
    }

    @Test
    void geplandeRondeOngeldigMelden_voertDeHandlerUit() {
        long id = plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));

        taakWorker.ongeldigMelden();

        assertTrue(zoek(id).isEmpty());
    }

    @Test
    void achterstandmetriek_teltOpenTakenDieAanDeBeurtZijn() {
        plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        plan(TaakSoort.ONGELDIG_MELDEN, nu().plusHours(1));

        assertEquals(1.0, meterRegistry.get("nmc.taken.achterstand").tag("soort", "ONGELDIG_MELDEN").gauge().value());
    }

    @Test
    void taakUitkomst_uitgesteldZonderDue_weigert() {
        assertThrows(NullPointerException.class, () -> TaakUitkomst.uitgesteld(null, false));
    }

    @Test
    void taak_payloadIsEenKopieEnNooitNull() {
        Taak metPayload = new Taak(TaakSoort.ONGELDIG_MELDEN, null, null, nu(), null, Map.of("k", "v"));
        Taak zonderPayload = new Taak(TaakSoort.ONGELDIG_MELDEN, null, null, nu(), null, null);

        assertEquals(Map.of("k", "v"), metPayload.getPayload());
        assertEquals(Map.of(), zonderPayload.getPayload());
        assertNotNull(metPayload.getSoort());
    }

    // Het aantal aanroepen van de handler is het aantal geclaimde taken; de teller bewijst dat de
    // worker elke taak precies één keer aanbiedt.
    @Test
    void verwerk_meerdereTaken_biedtElkeTaakEenKeerAan() {
        for (int i = 0; i < 3; i++) {
            plan(TaakSoort.ONGELDIG_MELDEN, nu().minusMinutes(1));
        }
        AtomicInteger aanroepen = new AtomicInteger();
        TestTaakHandler.GEDRAG.set((taak, lease) -> {
            aanroepen.incrementAndGet();

            return TaakUitkomst.afgerond();
        });

        assertEquals(3, taakWorker.verwerk(TaakSoort.ONGELDIG_MELDEN));
        assertEquals(3, aanroepen.get());
        assertEquals(0, taakRepository.count());
    }

    private long plan(TaakSoort soort, OffsetDateTime due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(soort, NotificatieFixtures.DV_ID, null, due, null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });
    }

    private java.util.Optional<Taak> zoek(long id) {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.findByIdOptional(id));
    }

    private void zetDue(long id, OffsetDateTime due) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE taak SET due = ?2 WHERE id = ?1")
                .setParameter(1, id).setParameter(2, due).executeUpdate());
    }

    private void zetLease(long id, OffsetDateTime tot) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE taak SET lease_tot = ?2 WHERE id = ?1")
                .setParameter(1, id).setParameter(2, tot).executeUpdate());
    }

    private static OffsetDateTime nu() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
