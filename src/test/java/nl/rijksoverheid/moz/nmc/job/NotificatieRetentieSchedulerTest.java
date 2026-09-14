package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.service.NotificatieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

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
    NotificatieService notificatieService;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    // Geen onderscheid naar status: elke status verloopt op dezelfde bewaartermijn.
    @ParameterizedTest
    @EnumSource(StatusWaarde.class)
    void verwijderVerlopenNotificaties_verwijdertElkeStatusOuderDanDeBewaartermijn(StatusWaarde status) {
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
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // Alle andere tests werken met uitersten (31 dagen oud versus 1 dag oud) rond de standaard
    // bewaartermijn van 30 dagen en zouden daarom net zo goed slagen met een hardgecodeerde grens.
    // Deze test draait een scheduler met een bewaartermijn van 2 dagen op een populatie waarvan
    // beide rijen ruim bínnen de standaardtermijn vallen: alleen als de geconfigureerde waarde de
    // grens écht bepaalt, verdwijnt de rij van 3 dagen oud en blijft die van 1 dag oud staan.
    // Een kale scheduler i.p.v. een @TestProfile: dat scheelt een tweede Quarkus-context, en de
    // constructorparameter is precies wat hier bewezen moet worden (zie verwijderBatchOp voor waarom
    // rechtstreeks construeren hier veilig is).
    @Test
    void verwijderVerlopenNotificaties_metEenAfwijkendeBewaartermijn_gebruiktDieAlsGrens() {
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
        UUID nietVerlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(2)).verwijderVerlopenNotificaties();

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

        plantVerlopenNotificaties(aantalRijen, verlopenTijdstip, "DELIVERED");

        scheduler.verwijderVerlopenNotificaties();

        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(0L, overgebleven);
    }

    // Vult aan op de vorige test: die bewijst dat de lus doorgaat tot alles weg is, maar zou ook
    // slagen voor een enkele onbegrensde DELETE (geen batching), het eindresultaat is hetzelfde.
    // Deze test roept verwijderBatch (privé; via reflectie, zie verwijderBatchOp voor waarom er geen
    // productiecode-zichtbaarheid voor wordt opgerekt) rechtstreeks aan en bewijst dat één aanroep
    // écht begrensd is tot BATCH_GROOTTE.
    @Test
    void verwijderBatch_metMeerKandidatenDanBatchGrootte_verwijdertPreciesBatchGrootte() {
        int aantalRijen = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        plantVerlopenNotificaties(aantalRijen, verlopenTijdstip, "DELIVERED");

        // grens is "nu", niet exact verlopenTijdstip: een gelijke grens loopt tegen
        // afrondingsverschil in de timestamp(6)-kolom aan (opgeslagen waarde vs. in-memory waarde
        // met nanoseconden), terwijl er hier alleen "ruim verlopen" getoetst hoeft te worden.
        int verwijderd = QuarkusTransaction.requiringNew()
                .call(() -> verwijderBatchOp(OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(1000, verwijderd);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(5L, overgebleven);
    }

    // Bewijst de transactie-per-batch waar de hele veiligheidsredenering van de job op rust: zonder
    // QuarkusTransaction.requiringNew() per batch (bijv. als verwijderVerlopenNotificaties() ooit
    // "vereenvoudigd" wordt tot één @Transactional-methode) zou een fout in batch 2 ook de al
    // verwijderde 1000 rijen van batch 1 terugdraaien, en zou elke andere test in deze suite gewoon
    // groen blijven.
    //
    // De storing wordt afgedwongen door getEntityManager() vanaf de derde aanroep te laten falen:
    // een run roept hem twee keer per batch aan (de claim met FOR UPDATE SKIP LOCKED en de DELETE)
    // en verder nergens — de melding zit sinds de herziening in de batchtransactie en gebruikt de
    // rijen die de claim al heeft opgehaald. Aanroep 1 en 2 zijn dus batch 1, aanroep 3 is de claim
    // van batch 2. De echte EntityManager wordt vooraf opgehaald zodat de eerste twee werken.
    @Test
    void verwijderVerlopenNotificaties_alsEenLatereBatchFaalt_blijftDeEerdereBatchVerwijderd() {
        plantVerlopenNotificaties(1500, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), "DELIVERED");

        EntityManager echteEntityManager = notificatieRepository.getEntityManager();
        AtomicInteger aanroepen = new AtomicInteger();
        doAnswer(invocation -> {
            if (aanroepen.incrementAndGet() > 2) {
                throw new RuntimeException("gesimuleerde storing in batch 2");
            }

            return echteEntityManager;
        }).when(notificatieRepository).getEntityManager();

        assertThrows(RuntimeException.class, scheduler::verwijderVerlopenNotificaties);

        // Stubbing weg vóór de telling, anders zou die zelf op de gesimuleerde storing stuklopen.
        Mockito.reset(notificatieRepository);
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(500L, overgebleven, "Batch 1 was al gecommit en mag niet zijn teruggedraaid door "
                + "de fout in batch 2");
    }

    // De melding van niet-definitieve kandidaten is signalering en draait vóór de batchlus; een
    // storing daarin mag de opruiming van die nacht niet tegenhouden. De eerste getEntityManager()-
    // aanroep is de telquery van die melding, dus alleen die faalt hier; alles daarna werkt gewoon.
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
        UUID notifyNlReferentie = UUID.randomUUID();
        UUID bezorgdId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID sendingId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31),
                notifyNlReferentie);

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.van(NotificatieRetentieScheduler.class)) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        List<String> perNotificatie = meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .toList();
        assertEquals(1, perNotificatie.size(), "alleen de niet-definitieve notificatie hoort gemeld");
        assertTrue(perNotificatie.getFirst().contains("notificatieId=" + sendingId));
        assertTrue(perNotificatie.getFirst().contains("notifyNlReferentie=" + notifyNlReferentie));
        assertTrue(perNotificatie.getFirst().contains("status=SENDING"));
        assertTrue(meldingen.stream().noneMatch(regel -> regel.contains(bezorgdId.toString())));
    }

    // Een notificatie die nog niet verlopen is, hoort niet gemeld te worden ook al is zijn status
    // niet-definitief: de melding gaat over de bewaartermijn volmaken zonder eindstatus, niet over
    // elke notificatie die nog onderweg is.
    @Test
    void verwijderVerlopenNotificaties_meldtGeenNietVerlopenNotificatie() {
        maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.van(NotificatieRetentieScheduler.class)) {
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
        plantVerlopenNotificaties(150, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), "SENDING");

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.van(NotificatieRetentieScheduler.class)) {
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
        UUID jongsteId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID oudsteId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(90));
        UUID middelsteId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(60));

        List<String> meldingen;
        try (LogVanger vanger = LogVanger.van(NotificatieRetentieScheduler.class)) {
            scheduler.verwijderVerlopenNotificaties();
            meldingen = vanger.regelsOpNiveau(Level.WARNING);
        }

        List<String> gemeldeIds = meldingen.stream()
                .filter(regel -> regel.contains("verlopen zonder eindstatus notificatieId="))
                .map(regel -> regel.replaceAll(".*notificatieId=(\\S+).*", "$1"))
                .toList();
        assertEquals(List.of(oudsteId.toString(), middelsteId.toString(), jongsteId.toString()), gemeldeIds);
    }

    // Bewaakt dat de retentie op het láátste geschiedenisrecord vaart en niet op een willekeurig
    // record: een oude aanmaakstatus mag een notificatie niet laten verwijderen als er nadien een
    // recentere status is bijgekomen. Zou laatsteStatusUpdate per ongeluk het eerste record volgen,
    // dan zou deze notificatie ten onrechte op zijn verlopen CREATED-datum verwijderd worden.
    @Test
    void verwijderVerlopenNotificaties_notificatieMetOudeCreatedMaarRecenteStatus_wordtNietVerwijderd() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            vervangGeschiedenisDoor(notificatie, List.of(
                    NotificatieStatus.opEigenKlok(StatusWaarde.CREATED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(40)),
                    NotificatieStatus.opEigenKlok(StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1))));
            notificatieRepository.persist(notificatie);
            return notificatie.getId();
        });

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(id).isPresent()));
    }

    // Meerdere statusregels per notificatie mogen de batchlus niet in de war sturen: de kandidaten
    // worden op notificatie geselecteerd, niet op statusregel, dus een notificatie telt precies één
    // keer mee ongeacht hoe lang zijn geschiedenis is.
    @Test
    void verwijderVerlopenNotificaties_metMeerdereStatussenPerNotificatie_verwijdertUiteindelijkAlles() {
        int aantalNotificaties = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        plantVerlopenNotificaties(aantalNotificaties, verlopenTijdstip, "SENDING", "DELIVERED");

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
        UUID nietVerlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);

        int verwijderd = QuarkusTransaction.requiringNew().call(() -> verwijderBatchOp(grens));

        assertEquals(0, verwijderd);
        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent()));
    }

    @Test
    void verwijderBatch_metEenVerlopenNotificatie_verwijdertDieWel() {
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        OffsetDateTime grens = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);

        int verwijderd = QuarkusTransaction.requiringNew().call(() -> verwijderBatchOp(grens));

        assertEquals(1, verwijderd);
        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty()));
    }

    // Bewust vastgelegd gedrag, geen toevalstreffer: een notificatie mét callbackUrl waarvan de
    // callback naar de Dienstverlener nooit is gelukt, wordt na de bewaartermijn alsnog verwijderd.
    // Verwijderen is volledig losgekoppeld van het afleveren van de callback (zie
    // NotificatieService.verwerkAfleverstatus).
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokNotificatiesWaarvanDeCallbackNooitIsGelukt() {
        UUID verlopenIdMetCallbackUrl = maakNotificatie("https://omc.example.nl/callback-die-nooit-lukt",
                StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(verlopenIdMetCallbackUrl).isEmpty()));
    }

    // De tegenhanger van de test hierboven: die bewijst de negatieve helft (het afleveren van een
    // status aan de Dienstverlener verzet de bewaartermijn níet), deze de positieve helft: een
    // binnenkomende NotifyNL-statusupdate registreert een nieuw statusgeschiedenisrecord en zet de
    // teller daarmee terug op nu. De notificatie start bewust ruim verlopen (31 dagen, bij de
    // standaardtermijn van 30): zou de statusupdate de bewaartermijn niet verzetten, dan haalt de
    // retentiejob hem hier alsnog weg. SENDING naar DELIVERED is een realistische opeenvolging die
    // niet door de afwijzing van een niet-definitieve status ná een definitieve wordt tegengehouden
    // (zie NotificatieService#verwerkAfleverstatus).
    @Test
    void verwerkAfleverstatus_voorEenVerlopenNotificatie_verzetDeBewaartermijnZodatDeRetentiejobHemLaatStaan() {
        UUID notifyNlReferentie = UUID.randomUUID();
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.setExternalReference(notifyNlReferentie);
            vervangGeschiedenisDoor(notificatie, List.of(NotificatieStatus.opEigenKlok(StatusWaarde.SENDING,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(31))));
            notificatieRepository.persist(notificatie);
            return notificatie.getId();
        });

        // Met een completed_at die zelf al buiten de bewaartermijn valt: de gebeurtenistijd komt van
        // de klok van NotifyNL en mag de bewaartermijn niet bepalen. Vaart de retentiejob er toch op,
        // dan verdwijnt deze notificatie terwijl er zojuist nog een receipt over binnenkwam.
        notificatieService.verwerkAfleverstatus(notifyNlReferentie, "delivered",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(id).isPresent()));
    }

    // Deze test dekt wat Hibernate zelf al doet: bij een JPQL bulk-delete ruimt Hibernate de
    // @ElementCollection-rijen (notificatie_status) zelf op vóórdat het notificatie-record
    // verdwijnt, de ON DELETE CASCADE-foreignkey wordt hier niet aangesproken. Let op: dit draait
    // op H2 (de teststack), dat een andere mutation-strategy gebruikt dan Postgres (productie); de
    // FK is en blijft het vangnet dat op beide dialecten hoort te werken (zie de aparte
    // ...ViaForeignKeyCascade-test hieronder, die de FK zelf dwingt, dialect-onafhankelijk).
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokDeStatusGeschiedenis() {
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        assertTrue(aantalNotificatieStatussenVoor(verlopenId) > 0);

        scheduler.verwijderVerlopenNotificaties();

        assertEquals(0L, aantalNotificatieStatussenVoor(verlopenId));
    }

    // In tegenstelling tot de test hierboven gaat deze buiten Hibernate om: een rechtstreekse SQL
    // DELETE op notificatie triggert geen enkele Hibernate-cascade-logica, dus dit is de enige test
    // die de DB-foreignkey (ON DELETE CASCADE in V2__notificatie_retentie.sql) daadwerkelijk dwingt
    // om de statusgeschiedenis op te ruimen.
    @Test
    void notificatieVerwijderenViaRuweSql_verwijdertStatusGeschiedenisViaForeignKeyCascade() {
        UUID id = maakNotificatie(null, StatusWaarde.CREATED, OffsetDateTime.now(ZoneOffset.UTC));
        assertTrue(aantalNotificatieStatussenVoor(id) > 0);

        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.getEntityManager()
                .createNativeQuery("DELETE FROM notificatie WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate());

        assertEquals(0L, aantalNotificatieStatussenVoor(id));
    }

    private UUID maakNotificatie(String callbackUrl, StatusWaarde status, OffsetDateTime laatsteStatusUpdate) {
        return maakNotificatie(callbackUrl, status, laatsteStatusUpdate, null);
    }

    private UUID maakNotificatie(String callbackUrl, StatusWaarde status, OffsetDateTime laatsteStatusUpdate,
            UUID externalReference) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(callbackUrl);
            notificatie.setExternalReference(externalReference);
            vervangGeschiedenisDoor(notificatie, List.of(NotificatieStatus.opEigenKlok(status, laatsteStatusUpdate)));
            notificatieRepository.persist(notificatie);
            return notificatie.getId();
        });
    }

    // De constructor registreert altijd zelf CREATED@now(); deze fixtures willen een bewust
    // terug- of vooruitgedateerde geschiedenis. Vervangt daarom de hele lijst door precies de
    // gewenste record(s), inclusief de projectie die registreerStatus normaal bijwerkt.
    private static void vervangGeschiedenisDoor(Notificatie notificatie, List<NotificatieStatus> geschiedenis) {
        zetVeld(notificatie, "statusGeschiedenis", new ArrayList<>(geschiedenis));
        zetVeld(notificatie, "laatsteStatus", geschiedenis.get(geschiedenis.size() - 1));
    }

    private static void zetVeld(Notificatie notificatie, String naam, Object waarde) {
        try {
            Field veld = Notificatie.class.getDeclaredField(naam);
            veld.setAccessible(true);
            veld.set(notificatie, waarde);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // De geschiedenis leeft in notificatie_status, een aparte tabel van notificatie (zie
    // V2__notificatie_retentie.sql), vandaar een apart INSERT...SELECT per status. RANDOM_UUID() in
    // losse statements zou niet corresponderen tussen beide tabellen; een deterministisch UUID
    // afgeleid van SYSTEM_RANGE's rijnummer (X) laat alle inserts voor dezelfde rij naar dezelfde
    // gegenereerde notificatie-id verwijzen. De projectiekolommen op notificatie worden hier
    // rechtstreeks gezet (de laatst meegegeven status geldt als de laatste), omdat deze fixture
    // Notificatie#registreerStatus bewust overslaat.
    private void plantVerlopenNotificaties(int aantalRijen, OffsetDateTime tijdstip, String... statussen) {
        String idExpressie = "CAST(('00000000-0000-0000-0000-' || LPAD(CAST(X AS VARCHAR), 12, '0')) AS UUID)";
        String laatsteStatus = statussen[statussen.length - 1];
        // volgnummer is de @OrderColumn van Notificatie#statusGeschiedenis en nul-gebaseerd; deze
        // fixture schrijft rechtstreeks SQL en moet hem dus zelf meetellen.
        int[] volgnummerHouder = {0};
        QuarkusTransaction.requiringNew().run(() -> {
            notificatieRepository.getEntityManager()
                    .createNativeQuery("INSERT INTO notificatie (id, laatste_status, laatste_status_tijdstip, "
                            + "laatste_status_update) SELECT "
                            + idExpressie + ", '" + laatsteStatus + "', ?1, ?1 FROM SYSTEM_RANGE(1, ?2)")
                    .setParameter(1, tijdstip)
                    .setParameter(2, aantalRijen)
                    .executeUpdate();
            for (String status : statussen) {
                notificatieRepository.getEntityManager()
                        .createNativeQuery("INSERT INTO notificatie_status (notificatie_id, volgnummer, status, tijdstip, "
                                + "geregistreerd) SELECT " + idExpressie + ", ?3, '" + status + "', ?1, ?1 "
                                + "FROM SYSTEM_RANGE(1, ?2)")
                        .setParameter(1, tijdstip)
                        .setParameter(2, aantalRijen)
                        .setParameter(3, volgnummerHouder[0]++)
                        .executeUpdate();
            }
        });
    }

    // Reflecteert op een rechtstreeks geconstrueerde instantie, niet op het @Inject-veld: dat laatste
    // is een CDI-clientproxy waarvan de eigen velden (notificatieRepository) leeg zijn, een privé
    // methode via reflectie op de proxy aanroepen omzeilt de CDI-delegatie en geeft een NPE. De
    // meegegeven Duration doet er niet toe: verwijderBatch gebruikt alleen de grens-parameter.
    private int verwijderBatchOp(OffsetDateTime grens) {
        NotificatieRetentieScheduler.BatchResultaat resultaat =
                (NotificatieRetentieScheduler.BatchResultaat) roepPrivateMethodeAan("verwijderBatch",
                        new Class<?>[] {OffsetDateTime.class, int.class}, grens, 0);

        return resultaat.verwijderd();
    }


    private Object roepPrivateMethodeAan(String naam, Class<?>[] parameterTypes, Object... argumenten) {
        try {
            NotificatieRetentieScheduler kaleScheduler =
                    new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(30));
            Method methode = NotificatieRetentieScheduler.class.getDeclaredMethod(naam, parameterTypes);
            methode.setAccessible(true);
            return methode.invoke(kaleScheduler, argumenten);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // NotificatieStatus is een @Embeddable (@ElementCollection), dus niet zelfstandig bevraagbaar
    // via JPQL: de collection-tabel wordt hier rechtstreeks met SQL geteld, ook zodat deze telling
    // blijft werken nadat de bijbehorende Notificatie al is verwijderd.
    private long aantalNotificatieStatussenVoor(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) notificatieRepository.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM notificatie_status WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult()).longValue());
    }
}
