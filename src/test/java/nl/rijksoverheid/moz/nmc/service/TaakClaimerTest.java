package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: 20 tokens per minuut, 5 gereserveerd voor de navraag, lease 2m.
@QuarkusTest
class TaakClaimerTest {

    private static final UUID TWEEDE_DV = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Inject
    TaakClaimer taakClaimer;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            entityManager.createNativeQuery("DELETE FROM verzendbudget").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO dienstverlener (id, oin, naam) VALUES (?1, '00000000000000000002', 'Tweede') "
                            + "ON CONFLICT (id) DO NOTHING")
                    .setParameter(1, TWEEDE_DV)
                    .executeUpdate();
        });
    }

    @Test
    void claim_neemtAlleenTakenDieAanDeBeurtZijn_oudsteEerst() {
        long later = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().plusHours(1));
        long oud = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusHours(2));
        long recent = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));

        List<Taak> geclaimd = taakClaimer.claim(TaakSoort.CONTROLE, 10);

        assertEquals(List.of(oud, recent), geclaimd.stream().map(Taak::getId).toList());
        assertEquals(1, geclaimd.getFirst().getClaimEpoch());
        assertTrue(geclaimd.getFirst().getLeaseTot().isAfter(nu().plusMinutes(1)));
        assertNull(zoek(later).getLeaseTot());
    }

    @Test
    void claim_geclaimdeTaak_isNietOpnieuwTeClaimenZolangDeLeaseLoopt() {
        plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));

        assertEquals(1, taakClaimer.claim(TaakSoort.CONTROLE, 10).size());
        assertEquals(0, taakClaimer.claim(TaakSoort.CONTROLE, 10).size());
    }

    // Een verlopen lease betekent dat de worker is uitgevallen; de taak gaat naar de volgende claim,
    // met een hoger epoch zodat de oude worker niets meer kan afronden.
    @Test
    void claim_verlopenLease_leidtTotHerclaimMetHogerEpoch() {
        long id = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        Taak eerste = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();
        laatLeaseVerlopen(id);

        Taak tweede = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();

        assertEquals(id, tweede.getId());
        assertEquals(eerste.getClaimEpoch() + 1, tweede.getClaimEpoch());
        assertThrows(TaakVerlorenException.class, () -> taakClaimer.rondAf(eerste));
        assertEquals(TaakStatus.OPEN, zoek(id).getStatus(), "de oude worker mocht de taak niet afronden");
        taakClaimer.rondAf(tweede);
        assertTrue(taakRepository.findByIdOptional(id).isEmpty());
    }

    // De epochtoets geldt voor elke schrijfactie, ook in de transactie van een handler: de exceptie
    // laat die transactie terugrollen.
    @Test
    void rondAf_binnenEenTransactieMetVerlorenLease_rolDeTransactieTerug() {
        long id = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        Taak eerste = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();
        laatLeaseVerlopen(id);
        taakClaimer.claim(TaakSoort.CONTROLE, 10);
        UUID markering = UUID.randomUUID();

        assertThrows(TaakVerlorenException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("INSERT INTO dienstverlener (id, oin, naam) VALUES (?1, 'markering00000000001', 'markering')")
                    .setParameter(1, markering)
                    .executeUpdate();
            taakClaimer.rondAf(eerste);
        }));

        assertEquals(0, ((Number) QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT COUNT(*) FROM dienstverlener WHERE id = ?1").setParameter(1, markering)
                .getSingleResult())).intValue(), "het werk in dezelfde transactie is teruggerold");
    }

    @Test
    void stelUit_zetDueEnLaatDeLeaseLos_enTeltAlleenAlsGevraagdEenPoging() {
        long id = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        Taak taak = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();
        OffsetDateTime straks = nu().plusMinutes(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        taakClaimer.stelUit(taak, straks, false);
        Taak naEersteUitstel = zoek(id);
        assertEquals(straks.toInstant(), naEersteUitstel.getDue().toInstant());
        assertNull(naEersteUitstel.getLeaseTot());
        assertEquals(0, naEersteUitstel.getPogingen());

        taakClaimer.stelUit(taak, straks, true);
        assertEquals(1, zoek(id).getPogingen());
    }

    @Test
    void markeerMislukt_zetStatusEnHoudtDeTaakUitDeClaim() {
        long id = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        Taak taak = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();

        taakClaimer.markeerMislukt(taak);

        assertEquals(TaakStatus.MISLUKT, zoek(id).getStatus());
        laatLeaseVerlopen(id);
        assertEquals(0, taakClaimer.claim(TaakSoort.CONTROLE, 10).size());
        assertEquals(1, taakRepository.telMetStatus(TaakSoort.CONTROLE, TaakStatus.MISLUKT));
    }

    @Test
    void verleng_zetDeLeaseVerderVooruit() {
        long id = plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        Taak taak = taakClaimer.claim(TaakSoort.CONTROLE, 10).getFirst();
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE taak SET lease_tot = ?2 WHERE id = ?1")
                .setParameter(1, id).setParameter(2, nu().plusSeconds(5)).executeUpdate());

        taakClaimer.verleng(taak);

        assertTrue(zoek(id).getLeaseTot().isAfter(nu().plusMinutes(1)));
    }

    // Twee workers op dezelfde achterstand krijgen elk een deel en nooit dezelfde taak.
    @Test
    void claim_tweeGelijktijdigeWorkers_claimenNooitDezelfdeTaak() throws Exception {
        for (int i = 0; i < 20; i++) {
            plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            List<Future<List<Taak>>> resultaten = new ArrayList<>();

            for (int w = 0; w < 2; w++) {
                resultaten.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);

                    return taakClaimer.claim(TaakSoort.CONTROLE, 20);
                }));
            }

            start.countDown();
            Set<Long> gezien = new HashSet<>();
            int totaal = 0;

            for (Future<List<Taak>> resultaat : resultaten) {
                for (Taak taak : resultaat.get(20, TimeUnit.SECONDS)) {
                    assertTrue(gezien.add(taak.getId()), "taak " + taak.getId() + " is twee keer geclaimd");
                    totaal++;
                }
            }

            assertEquals(20, totaal);
        } finally {
            workers.shutdownNow();
        }
    }

    // De batch wordt over de dienstverleners met werk verdeeld, zodat een piek van de ene de andere
    // niet blokkeert.
    @Test
    void claim_meerdereDienstverleners_verdeeltDeBatch() {
        for (int i = 0; i < 5; i++) {
            plan(TaakSoort.CONTROLE, NotificatieFixtures.DV_ID, nu().minusHours(2));
            plan(TaakSoort.CONTROLE, TWEEDE_DV, nu().minusMinutes(1));
        }

        List<Taak> geclaimd = taakClaimer.claim(TaakSoort.CONTROLE, 4);

        assertEquals(4, geclaimd.size());
        assertEquals(2, geclaimd.stream().filter(t -> NotificatieFixtures.DV_ID.equals(t.getDvId())).count());
        assertEquals(2, geclaimd.stream().filter(t -> TWEEDE_DV.equals(t.getDvId())).count());
    }

    @Test
    void claim_takenPerSysteem_zonderDienstverlener() {
        long id = plan(TaakSoort.ONDERHOUD, null, nu().minusMinutes(1));

        List<Taak> geclaimd = taakClaimer.claim(TaakSoort.ONDERHOUD, 10);

        assertEquals(List.of(id), geclaimd.stream().map(Taak::getId).toList());
        assertNull(geclaimd.getFirst().getDvId());
    }

    @Test
    void claim_zonderWerk_geeftLegeLijst() {
        assertEquals(List.of(), taakClaimer.claim(TaakSoort.WISSEN, 10));
    }

    // Verzenden claimt tegen het budget: 20 tokens min 5 voor de navraag, en niet meer. Een uitgeput
    // budget stelt de claim uit zonder dat een taak wordt aangeraakt.
    @Test
    void claim_verzenden_isBegrensdDoorHetVerzendbudget() {
        for (int i = 0; i < 30; i++) {
            plan(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        }

        assertEquals(15, taakClaimer.claim(TaakSoort.VERZENDEN, 100).size());
        assertEquals(0, taakClaimer.claim(TaakSoort.VERZENDEN, 100).size(), "budget voor dit tijdvak is op");
        assertEquals(15, taakRepository.count("soort = ?1 and leaseTot is null and pogingen = 0", TaakSoort.VERZENDEN),
                "de niet-geclaimde taken zijn onaangeraakt");
    }

    // Wat wel uit het budget kwam maar niet nodig was, gaat terug: drie taken kosten drie tokens.
    @Test
    void claim_verzenden_geeftOngebruikteTokensTerug() {
        for (int i = 0; i < 3; i++) {
            plan(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, nu().minusMinutes(1));
        }

        assertEquals(3, taakClaimer.claim(TaakSoort.VERZENDEN, 100).size());

        int tokens = QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager
                .createNativeQuery("SELECT tokens FROM verzendbudget").getSingleResult()).intValue());
        assertEquals(17, tokens);
    }

    @Test
    void constructor_nietPositieveLease_weigert() {
        assertThrows(IllegalStateException.class, () -> new TaakClaimer(taakRepository, null, Duration.ZERO));
    }

    private long plan(TaakSoort soort, UUID dvId, OffsetDateTime due) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(soort, dvId, null, due, "trace-" + UUID.randomUUID(), Map.of("k", "v"));
            taakRepository.persist(taak);

            return taak.getId();
        });
    }

    private Taak zoek(long id) {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.findById(id));
    }

    private void laatLeaseVerlopen(long id) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE taak SET lease_tot = ?2 WHERE id = ?1")
                .setParameter(1, id).setParameter(2, nu().minusSeconds(1)).executeUpdate());
    }

    private static OffsetDateTime nu() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
