package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.repository.Kandidaat;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.service.ReceiptVerwerker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;

import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class NotificatieRetentieSchedulerTest {

    // @InjectSpy i.p.v. @Inject: zonder stubbing gedraagt de spy zich als de echte repository, dus
    // alle tests hieronder draaien gewoon tegen de echte database. Alleen
    // ...alsEenLatereBatchFaalt_... stubt hem, om een storing halverwege de batchlus af te dwingen.
    @InjectSpy
    NotificatieRepository notificatieRepository;

    @Inject
    NotificatieRetentieScheduler scheduler;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    // Vangnet naast de resets in de tests zelf: gooit een test onverwacht, dan lekt de stubbing
    // anders naar de volgende test in deze klasse.
    @AfterEach
    void resetSpy() {
        Mockito.reset(notificatieRepository);
    }

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    // Geen onderscheid naar status: elke status verloopt op dezelfde bewaartermijn.
    @ParameterizedTest
    @EnumSource(NotificatieStatus.class)
    void verwijderVerlopenNotificaties_verwijdertElkeStatusOuderDanDeBewaartermijn(NotificatieStatus status) {
        UUID verlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // Dekt de realistische situatie waarin verschillende statussen tegelijk in de tabel staan: enkel
    // ouderdom bepaalt of een rij weg moet, de status zelf doet er niet toe.
    @Test
    void verwijderVerlopenNotificaties_metGemengdePopulatie_verwijdertAlleenDeVerlopenRijen() {
        UUID verlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // De andere tests werken met uitersten rond de standaardtermijn en zouden ook met een
    // hardgecodeerde grens slagen. Hier vallen beide rijen bínnen die standaardtermijn, dus alleen een
    // scheduler die de geconfigureerde 2 dagen gebruikt laat de rij van 3 dagen oud verdwijnen.
    @Test
    void verwijderVerlopenNotificaties_metEenAfwijkendeBewaartermijn_gebruiktDieAlsGrens() {
        UUID verlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
        UUID nietVerlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        new NotificatieRetentieScheduler(notificatieRepository, new RetentieConfiguratie(Duration.ofDays(2), 10_000)).verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // De verwijdering gebeurt in batches (zie NotificatieRetentieScheduler#BATCH_GROOTTE = 1000) om
    // de transactie begrensd te houden ongeacht de achterstand. Dit zet er express meer dan één
    // batch aan verlopen rijen neer om aan te tonen dat de lus doorgaat tot alles weg is, niet dat
    // hij na de eerste batch stopt. Rijen worden met ruwe SQL geplant (i.p.v. maakNotificatie 1000+
    // keer aan te roepen) puur om de test snel te houden: dit test geen entiteitgedrag, alleen de
    // batchlus.
    @Test
    void verwijderVerlopenNotificaties_meerDanEenBatchAanRijen_verwijdertUiteindelijkAlles() {
        int aantalRijen = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        plantVerlopenNotificaties(aantalRijen, verlopenTijdstip, NotificatieStatus.BEZORGD);

        scheduler.verwijderVerlopenNotificaties();

        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(0L, overgebleven);
    }

    // Vult aan op de vorige test: die bewijst dat de lus doorgaat tot alles weg is, maar zou ook
    // slagen voor een enkele onbegrensde DELETE (geen batching), het eindresultaat is hetzelfde.
    // Deze test gebruikt één RetentieBatch rechtstreeks en bewijst dat één batch écht begrensd is
    // tot RetentieBatch.GROOTTE.
    @Test
    void verwijderBatch_metMeerKandidatenDanBatchGrootte_verwijdertPreciesBatchGrootte() {
        int aantalRijen = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        plantVerlopenNotificaties(aantalRijen, verlopenTijdstip, NotificatieStatus.BEZORGD);

        // grens is "nu", niet exact verlopenTijdstip: een gelijke grens loopt tegen
        // afrondingsverschil in de timestamp(6)-kolom aan (opgeslagen waarde vs. in-memory waarde
        // met nanoseconden), terwijl er hier alleen "ruim verlopen" getoetst hoeft te worden.
        int verwijderd = QuarkusTransaction.requiringNew()
                .call(() -> verwijderBatchOp(OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(1000, verwijderd);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(5L, overgebleven);
    }

    // Bewijst de transactie-per-batch: zou verwijderVerlopenNotificaties() ooit één @Transactional
    // methode worden, dan draait een fout in batch 2 ook de 1000 al verwijderde rijen van batch 1
    // terug, terwijl elke andere test in deze suite groen blijft. De run zelf gooit niet — de lus
    // vangt een mislukte batch af — dus wat hier valt is de isolatie, niet de exceptie.
    @Test
    void verwijderVerlopenNotificaties_alsEenLatereBatchFaalt_blijftDeEerdereBatchVerwijderd() {
        plantVerlopenNotificaties(1500, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        AtomicInteger batches = new AtomicInteger();
        doAnswer(invocation -> {
            if (batches.incrementAndGet() > 1) {
                throw new RuntimeException("gesimuleerde storing vanaf batch 2");
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        scheduler.verwijderVerlopenNotificaties();

        Mockito.reset(notificatieRepository);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(500L, overgebleven, "Batch 1 was al gecommit en mag niet zijn teruggedraaid door "
                + "de fout in batch 2");
    }

    // Een batch die gooit mag de rest van de achterstand niet gijzelen. Voorheen ontsnapte de
    // exceptie uit de lus en stopte de hele run; omdat de claim op laatste_status_update ordent kwam
    // dezelfde rij de volgende nacht weer als eerste terug, en lag de opruiming permanent stil. De
    // lus slaat de geclaimde ids nu over en gaat door.
    @Test
    void verwijderVerlopenNotificaties_alsEenBatchFaalt_gaatDoorMetDeVolgende() {
        plantVerlopenNotificaties(1005, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        AtomicInteger batches = new AtomicInteger();
        doAnswer(invocation -> {
            if (batches.incrementAndGet() == 1) {
                throw new RuntimeException("gesimuleerde storing in batch 1");
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        scheduler.verwijderVerlopenNotificaties();

        Mockito.reset(notificatieRepository);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(1000L, overgebleven, "de 1000 rijen van de mislukte batch blijven staan, de rest "
                + "van de achterstand wordt alsnog opgeruimd");
    }

    // De bovengrens op het aantal batches was nooit bereikbaar in een test: 10.000 batches van 1000
    // betekent tien miljoen rijen planten. Nu de grens configureerbaar is, kan hij met een lage
    // waarde wél geraakt worden. Bewijst twee dingen: de run stopt bij de grens in plaats van door te
    // gaan tot alles weg is, en wat blijft staan blijft staan.
    @Test
    void verwijderVerlopenNotificaties_bovengrensBereikt_stoptEnLaatDeRestStaan() {
        plantVerlopenNotificaties(2500, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        new NotificatieRetentieScheduler(notificatieRepository, new RetentieConfiguratie(Duration.ofDays(7), 2))
                .verwijderVerlopenNotificaties();

        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(500L, overgebleven, "twee batches van 1000 verwijderd, de rest blijft tot de "
                + "volgende run");
    }

    // Verwijdert de DELETE minder dan er geclaimd is, dan hoort dat als mislukte batch te gelden.
    // Zonder die controle draait de lus door: klaar hangt aan het aantal geclaimde rijen, dus bij
    // nul verwijderd claimt de volgende ronde exact dezelfde rijen tot aan maxBatches — 10.000
    // rondes op dezelfde 1000 rijen, met een ERROR die naar het verkeerde probleem wijst.
    @Test
    void verwijderVerlopenNotificaties_alsDeDeleteMinderVerwijdertDanGeclaimd_slaatDieRijenOver() {
        plantVerlopenNotificaties(1005, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        AtomicInteger aanroepen = new AtomicInteger();
        doAnswer(invocation -> {
            if (aanroepen.incrementAndGet() == 1) {
                // Doet alsof de DELETE niets raakt, zonder te gooien: precies het geval dat de lus
                // eerder liet doordraaien.
                return 0;
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        scheduler.verwijderVerlopenNotificaties();

        Mockito.reset(notificatieRepository);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(1000L, overgebleven, "de 1000 rijen van de mislukte batch blijven staan, de rest "
                + "wordt alsnog opgeruimd — en de lus claimt ze niet eindeloos opnieuw");
    }

    // De meldingen worden vóór de DELETE weggeschreven, dus ze zijn er ook als de batch daarna
    // faalt. Werden ze niet meegeteld, dan kreeg de volgende batch het volle meldbudget opnieuw en
    // telde de samenvatting minder dan er detailregels onder staan.
    @Test
    void verwijderVerlopenNotificaties_alsEenBatchFaalt_teltDeAlGeschrevenMeldingenMee() {
        plantVerlopenNotificaties(1005, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.VERZONDEN);

        AtomicInteger aanroepen = new AtomicInteger();
        doAnswer(invocation -> {
            if (aanroepen.incrementAndGet() == 1) {
                throw new RuntimeException("gesimuleerde storing in batch 1");
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        Mockito.reset(notificatieRepository);
        assertEquals(100, meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .count(), "het meldbudget is voor de hele run, niet per batch");
    }

    // Een batch die pas bij de commit omvalt (JTA-timeout, verbroken verbinding) heeft zijn tellers
    // al gezet terwijl er niets verwijderd is. Telt de samenvatting die mee, dan meldt de job rijen
    // als opgeruimd die er nog staan.
    @Test
    void verwijderVerlopenNotificaties_alsDeCommitFaalt_teltDieBatchNietAlsVerwijderd() {
        plantVerlopenNotificaties(5, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.VERZONDEN);

        // Gooit ná de echte DELETE, dus met verwijderd en zonderEindstatus al gevuld; dat is wat een
        // storing bij de commit ook doet.
        doAnswer(invocation -> {
            invocation.callRealMethod();

            throw new RuntimeException("gesimuleerde storing bij de commit");
        }).when(notificatieRepository).verwijderOpId(any());

        List<String> samenvatting;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            samenvatting = vanger.regelsOpNiveau(Level.INFO);
        }

        Mockito.reset(notificatieRepository);
        assertEquals(1, samenvatting.size());
        assertTrue(samenvatting.getFirst().startsWith("Retentiejob: 0 verlopen notificatie(s) verwijderd"),
                samenvatting.getFirst());
        assertTrue(samenvatting.getFirst().contains("waarvan 0 zonder eindstatus"), samenvatting.getFirst());
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(5L, overgebleven, "de teruggerolde batch heeft niets verwijderd");
    }

    // De claim neemt een rijlock; daarop steunt de DELETE, die het retentiepredicaat niet herhaalt.
    // Zonder FOR UPDATE kan een gelijktijdige verwerkAfleverstatus de rij tussen claim en DELETE
    // bijwerken, waarna een notificatie met een verse status alsnog verdwijnt.
    @Test
    void claimVerlopen_houdtDeRijenVastVoorEenTweedeTransactie() throws Exception {
        UUID id = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        CountDownLatch geclaimd = new CountDownLatch(1);
        CountDownLatch losgelaten = new CountDownLatch(1);
        ExecutorService claimer = Executors.newSingleThreadExecutor();

        try {
            claimer.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                notificatieRepository.claimVerlopen(OffsetDateTime.now(ZoneOffset.UTC), 10, List.of());
                geclaimd.countDown();
                try {
                    losgelaten.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(geclaimd.await(10, TimeUnit.SECONDS));

            // Korte lock-timeout, anders wacht deze transactie tot de claimer klaar is in plaats van
            // te falen; het gaat erom dát de rij vastgehouden wordt.
            assertThrows(Exception.class, () -> QuarkusTransaction.requiringNew().run(() -> {
                notificatieRepository.getEntityManager().createNativeQuery("SET LOCK_TIMEOUT 250").executeUpdate();
                NotificatieFixtures.verzetLaatsteStatusUpdate(notificatieRepository.getEntityManager(), id,
                        OffsetDateTime.now(ZoneOffset.UTC));
            }), "een geclaimde rij hoort vast te staan tot de retentietransactie eindigt");
        } finally {
            losgelaten.countDown();
            claimer.shutdownNow();
            assertTrue(claimer.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    // De bulk-delete gaat over Notificatie; de pogingen gaan mee via de foreignkey. Geeft
    // executeUpdate() het aantal pogingen terug in plaats van het aantal notificaties, dan klopt de
    // controle in RetentieBatch niet meer en faalt elke batch.
    @Test
    void verwijderOpId_metMeerderePogingenPerNotificatie_geeftHetAantalNotificatiesTerug() {
        OffsetDateTime verlopen = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);
        List<UUID> ids = List.of(
                maakNotificatieMetPogingen(NotificatieStatus.BEZORGD, verlopen, 3),
                maakNotificatieMetPogingen(NotificatieStatus.BEZORGD, verlopen, 2));

        int verwijderd = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.verwijderOpId(ids));

        assertEquals(2, verwijderd, "het aantal notificaties, niet het aantal pogingen");
    }

    // Faalt de claim zelf, dan is er niets geclaimd en valt er ook niets uit te sluiten. De run moet
    // dan stoppen op de grens van opeenvolgende mislukkingen in plaats van eindeloos door te gaan.
    @Test
    void verwijderVerlopenNotificaties_alsDeClaimFaalt_stoptNaDeGrensVanOpeenvolgendeMislukkingen() {
        plantVerlopenNotificaties(5, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);
        doThrow(new RuntimeException("gesimuleerde storing in de claim"))
                .when(notificatieRepository).claimVerlopen(any(), anyInt(), any());

        assertThrows(RuntimeException.class, () -> scheduler.verwijderVerlopenNotificaties());

        // Verifiëren vóór de reset: die wist de opgenomen aanroepen.
        verify(notificatieRepository, times(5)).claimVerlopen(any(), anyInt(), any());
        Mockito.reset(notificatieRepository);
    }

    // Losse mislukkingen met geslaagde batches ertussen zetten de teller van opeenvolgende
    // mislukkingen terug; de totaalgrens hoort de run dan alsnog af te breken.
    @Test
    void verwijderVerlopenNotificaties_metVerspreideMislukkingen_stoptOpDeTotaalgrens() {
        plantVerlopenNotificaties(12_000, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        // Patroon van vier mislukkingen en één geslaagde batch: de teller op rij komt nooit aan vijf,
        // dus alleen de totaalgrens van tien kan deze run stoppen.
        AtomicInteger aanroepen = new AtomicInteger();
        doAnswer(invocation -> {
            if (aanroepen.incrementAndGet() % 5 != 0) {
                throw new RuntimeException("gesimuleerde storing in batch " + aanroepen.get());
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            assertThrows(RuntimeException.class, () -> scheduler.verwijderVerlopenNotificaties());
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        Mockito.reset(notificatieRepository);
        assertTrue(fouten.stream().anyMatch(regel -> regel.contains("10 mislukte batches in deze run")),
                "de totaalgrens hoort de run af te breken: " + fouten);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(10_000L, overgebleven, "twee geslaagde batches van 1000 verwijderd");
    }

    // Een run waarin batches mislukken mag qua samenvatting niet op een geslaagde run lijken.
    @Test
    void verwijderVerlopenNotificaties_alsEenBatchFaalt_meldtDatInDeSamenvatting() {
        plantVerlopenNotificaties(1005, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        AtomicInteger aanroepen = new AtomicInteger();
        doAnswer(invocation -> {
            if (aanroepen.incrementAndGet() == 1) {
                throw new RuntimeException("gesimuleerde storing in batch 1");
            }

            return invocation.callRealMethod();
        }).when(notificatieRepository).verwijderOpId(any());

        List<String> fouten;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        Mockito.reset(notificatieRepository);
        assertTrue(fouten.stream().anyMatch(regel -> regel.contains("1000 rij(en) deze run overgeslagen")),
                "de samenvatting hoort te melden hoeveel rijen zijn blijven staan");
    }

    // Blijven de batches falen, dan is er een storing en heeft doorgaan geen zin. De teller telt
    // opeenvolgende mislukkingen, zodat losse onverwijderbare rijen de rest van de achterstand niet
    // gijzelen.
    @Test
    void verwijderVerlopenNotificaties_alsBatchesBlijvenFalen_breektDeRunAf() {
        plantVerlopenNotificaties(6000, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.BEZORGD);

        doAnswer(invocation -> {
            throw new RuntimeException("structurele storing");
        }).when(notificatieRepository).verwijderOpId(any());

        assertThrows(RuntimeException.class, scheduler::verwijderVerlopenNotificaties);

        Mockito.reset(notificatieRepository);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(6000L, overgebleven, "er is niets verwijderd en de run stopt na vijf mislukkingen "
                + "op rij in plaats van door te gaan tot de bovengrens");
    }

    // De melding zit sinds de herziening ín de batchtransactie en gaat over exact de geclaimde rijen.
    // Daarmee is de oude test "als de melding faalt, verwijdert de job alsnog" vervallen: melding en
    // verwijdering kunnen niet meer los van elkaar slagen. Dat een falende batch zijn eigen rijen
    // laat staan, is gedekt door verwijderVerlopenNotificaties_alsEenLatereBatchFaalt_....

    // Pint de melding waar dashboards op gebouwd worden: elke verlopen notificatie zonder eindstatus
    // levert precies één WARN-regel op, een verlopen notificatie mét eindstatus niet, ook al worden
    // beide verwijderd. Zonder deze assertie kan het niveau of de regel verdwijnen zonder dat een
    // test valt, terwijl dit het enige signaal is dat overblijft nadat de rij weg is.
    @Test
    void verwijderVerlopenNotificaties_meldtAlleenDeNotificatiesZonderEindstatus() {
        UUID bezorgdId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID sendingId = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31),
                UUID.randomUUID());

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        List<String> perNotificatie = meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .toList();
        assertEquals(1, perNotificatie.size(), "alleen de niet-definitieve notificatie hoort gemeld");
        // Alle drie de velden afzonderlijk. De afbeelding op Kandidaat gebeurt door een
        // JPQL-constructorexpressie die Hibernate bij het opstarten valideert, dus een verkeerde
        // ariteit of type komt niet meer tot hier; deze asserties dekken dat de júiste kolommen
        // gekozen zijn.
        assertTrue(perNotificatie.getFirst().contains("notificatieId=" + sendingId));
        assertTrue(perNotificatie.getFirst().contains("status=VERZONDEN"));
        assertTrue(perNotificatie.getFirst().matches(".*laatsteStatusUpdate=\\d{4}-\\d{2}-\\d{2}T.*"),
                "het derde veld hoort een tijdstip te zijn, niet een van de andere kolommen");
        assertTrue(meldingen.stream().noneMatch(regel -> regel.contains(bezorgdId.toString())));
    }

    // Een notificatie die nog niet verlopen is, hoort niet gemeld te worden ook al is zijn status
    // niet-definitief: de melding gaat over de bewaartermijn volmaken zonder eindstatus, niet over
    // elke notificatie die nog onderweg is.
    @Test
    void verwijderVerlopenNotificaties_meldtGeenNietVerlopenNotificatie() {
        maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        assertTrue(meldingen.stream().noneMatch(regel -> regel.contains("verlopen zonder eindstatus")));
    }

    // De detailregels zijn begrensd op MAX_MELDINGEN (100); zonder die begrenzing zou een storing bij
    // NotifyNL de logs vullen met een regel per notificatie. Het totaal in de samenvatting blijft wél
    // volledig, zodat een dashboard dat daarop telt niet stilzwijgend afkapt.
    @Test
    void verwijderVerlopenNotificaties_metMeerRijenDanDeMeldgrens_kaptDetailregelsAfMaarNietDeTelling() {
        plantVerlopenNotificaties(150, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), NotificatieStatus.VERZONDEN);

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        assertEquals(100, meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .count());
        assertTrue(meldingen.stream().anyMatch(regel ->
                regel.contains("alleen de eerste 100 van 150")));
    }

    // De afgekapte lijst moet een reproduceerbare selectie zijn, niet een willekeurige greep: oudste
    // eerst, zodat de langst vastzittende notificaties gemeld worden. Zonder de ORDER BY in de
    // claim-query is de volgorde die de database teruggeeft niet gegarandeerd.
    @Test
    void verwijderVerlopenNotificaties_meldtDeOudsteEerst() {
        UUID jongsteId = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID oudsteId = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(90));
        UUID middelsteId = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(60));

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.vanPakket(NotificatieRetentieScheduler.class.getPackage())) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        List<String> gemeldeIds = meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .map(regel -> regel.replaceAll(".*notificatieId=(\\S+).*", "$1"))
                .toList();
        assertEquals(List.of(oudsteId.toString(), middelsteId.toString(), jongsteId.toString()), gemeldeIds);
    }

    // Bewaakt dat de retentie op de registratietijd van de laatste overgang vaart en niet op een
    // ouder event: een oude aanname mag een notificatie niet laten verwijderen als er nadien een
    // recentere overgang is geweest.
    @Test
    void verwijderVerlopenNotificaties_notificatieMetOudeAannameMaarRecenteStatus_wordtNietVerwijderd() {
        UUID id = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.voegEventToe(
                notificatieRepository.getEntityManager(), id, 0, null, NotificatieStatus.AANGENOMEN,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(40)));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(id).isPresent()));
    }

    // Meerdere pogingen per notificatie mogen de batchlus niet in de war sturen: de kandidaten
    // worden op notificatie geselecteerd, niet op poging, dus een notificatie telt precies één keer
    // mee ongeacht hoeveel verzendingen hij had.
    @Test
    void verwijderVerlopenNotificaties_metMeerderePogingenPerNotificatie_verwijdertUiteindelijkAlles() {
        int aantalNotificaties = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        plantVerlopenNotificaties(aantalNotificaties, verlopenTijdstip, NotificatieStatus.VERZONDEN, 2);

        scheduler.verwijderVerlopenNotificaties();

        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(0L, overgebleven);
    }

    // De TOCTOU die hier eerder getest werd — een kandidaat die tussen de SELECT en de DELETE een
    // verse statusregel kreeg — bestaat niet meer: verwijderBatch selecteert en verwijdert in één
    // statement. Wat blijft is dat het retentiepredicaat klopt, en dat toetsen deze twee: een
    // notificatie die niet verlopen is blijft staan, een die dat wel is gaat weg. Zonder de
    // tegenhanger zou de eerste assertie ook slagen als de DELETE nooit meer iets verwijdert.
    @Test
    void verwijderBatch_metEenNotificatieDieNietVerlopenIs_verwijdertDieNiet() {
        UUID nietVerlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);

        int verwijderd = QuarkusTransaction.requiringNew().call(() -> verwijderBatchOp(grens));

        assertEquals(0, verwijderd);
        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent()));
    }

    @Test
    void verwijderBatch_metEenVerlopenNotificatie_verwijdertDieWel() {
        UUID verlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);

        int verwijderd = QuarkusTransaction.requiringNew().call(() -> verwijderBatchOp(grens));

        assertEquals(1, verwijderd);
        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty()));
    }

    // Bewust vastgelegd gedrag, geen toevalstreffer: een notificatie mét callbackUrl waarvan de
    // callback naar de Dienstverlener nooit is gelukt, wordt na de bewaartermijn alsnog verwijderd.
    // Verwijderen is volledig losgekoppeld van het afleveren van de callback.
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokNotificatiesWaarvanDeCallbackNooitIsGelukt() {
        UUID verlopenIdMetCallbackUrl = maakNotificatie("https://omc.example.nl/callback-die-nooit-lukt",
                NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(verlopenIdMetCallbackUrl).isEmpty()));
    }

    // De positieve helft naast de test hierboven: een binnenkomende receipt voert een overgang uit en
    // zet de bewaartermijn terug op nu. De notificatie start ruim verlopen (31 dagen), dus zonder dat
    // effect zou de retentiejob hem hier weghalen.
    @Test
    void verwerkReceipt_voorEenVerlopenNotificatie_verzetDeBewaartermijnZodatDeRetentiejobHemLaatStaan() {
        UUID notifyId = UUID.randomUUID();
        UUID id = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31),
                notifyId);

        // Met een completed_at die zelf al buiten de bewaartermijn valt: de gebeurtenistijd komt van
        // de klok van NotifyNL en mag de bewaartermijn niet bepalen. Vaart de retentiejob er toch op,
        // dan verdwijnt deze notificatie terwijl er zojuist nog een receipt over binnenkwam.
        receiptVerwerker.verwerk(notifyId, null, "delivered", OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(id).isPresent()));
    }

    // De JPQL bulk-delete kent geen associatie naar Poging, dus Hibernate ruimt die niet zelf op; de
    // ON DELETE CASCADE op de foreignkey doet dat.
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokDePogingen() {
        UUID verlopenId = maakNotificatie(null, NotificatieStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31),
                UUID.randomUUID());

        assertTrue(aantalPogingenVoor(verlopenId) > 0);

        scheduler.verwijderVerlopenNotificaties();

        assertEquals(0L, aantalPogingenVoor(verlopenId));
    }

    // Buiten Hibernate om: een rechtstreekse SQL DELETE op notificatie dwingt de foreignkey zelf.
    @Test
    void notificatieVerwijderenViaRuweSql_verwijdertPogingenViaForeignKeyCascade() {
        UUID id = maakNotificatie(null, NotificatieStatus.VERZONDEN, OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID());
        assertTrue(aantalPogingenVoor(id) > 0);

        QuarkusTransaction.requiringNew().run(() ->
                NotificatieFixtures.verwijderNotificatie(notificatieRepository.getEntityManager(), id));

        assertEquals(0L, aantalPogingenVoor(id));
    }

    private UUID maakNotificatie(String callbackUrl, NotificatieStatus status, OffsetDateTime laatsteStatusUpdate) {
        return maakNotificatie(callbackUrl, status, laatsteStatusUpdate, null);
    }

    // De overgangsfunctie stempelt altijd de eigen klok van nu; deze fixtures willen een bewust
    // terug- of vooruitgedateerde registratietijd. Die schrijft NotificatieFixtures met SQL, met een
    // poging op het gegeven NotifyNL-id als dat is meegegeven.
    private UUID maakNotificatie(String callbackUrl, NotificatieStatus status, OffsetDateTime laatsteStatusUpdate,
            UUID notifyId) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.voegNotificatieToe(notificatieRepository.getEntityManager(), id, callbackUrl, status,
                    laatsteStatusUpdate);

            if (notifyId != null) {
                NotificatieFixtures.voegPogingToe(notificatieRepository.getEntityManager(), id, 1, notifyId,
                        PogingStatus.VERZONDEN, laatsteStatusUpdate);
            }
        });

        return id;
    }

    private UUID maakNotificatieMetPogingen(NotificatieStatus status, OffsetDateTime laatsteStatusUpdate, int aantalPogingen) {
        UUID id = maakNotificatie(null, status, laatsteStatusUpdate);
        QuarkusTransaction.requiringNew().run(() -> {
            for (int nummer = 1; nummer <= aantalPogingen; nummer++) {
                NotificatieFixtures.voegPogingToe(notificatieRepository.getEntityManager(), id, nummer, UUID.randomUUID(),
                        PogingStatus.VERZONDEN, laatsteStatusUpdate);
            }
        });

        return id;
    }

    private void plantVerlopenNotificaties(int aantalRijen, OffsetDateTime tijdstip, NotificatieStatus status) {
        plantVerlopenNotificaties(aantalRijen, tijdstip, status, 1);
    }

    private void plantVerlopenNotificaties(int aantalRijen, OffsetDateTime tijdstip, NotificatieStatus status,
            int aantalPogingen) {
        NotificatieFixtures.plantNotificaties(notificatieRepository.getEntityManager(), aantalRijen, tijdstip, status,
                aantalPogingen);
    }

    // Rechtstreeks één batch, zonder de lus eromheen: zo is de batchgrens te toetsen zonder
    // meerdere batches aan rijen te planten.
    private int verwijderBatchOp(OffsetDateTime grens) {
        RetentieBatch batch = new RetentieBatch(notificatieRepository, 0);
        batch.verwijder(grens, List.of());

        return batch.verwijderd();
    }

    private long aantalPogingenVoor(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() ->
                NotificatieFixtures.telPogingen(notificatieRepository.getEntityManager(), notificatieId));
    }
}
