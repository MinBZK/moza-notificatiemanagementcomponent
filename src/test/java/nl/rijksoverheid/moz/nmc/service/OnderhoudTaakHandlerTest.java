package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import nl.rijksoverheid.moz.nmc.repository.EventPartitieRepository;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: bewaartermijn afleverbewijs 365 dagen, maximale cursorleeftijd 30 dagen, batch 2, hoogstens
// 3 batches per stap, partities van 50 transactie-ids, huidige KEK-versie 2, cluster-epoch 1.
@QuarkusTest
class OnderhoudTaakHandlerTest {

    // base64 van "test-kek-niet-voor-productie-001": versie 1 uit de testconfiguratie.
    private static final String TEST_KEK_1 = "dGVzdC1rZWstbmlldC12b29yLXByb2R1Y3RpZS0wMDE=";
    private static final Ontvanger ONTVANGER = Ontvanger.email("burger@example.nl");

    @Inject
    OnderhoudTaakHandler onderhoudTaakHandler;

    @Inject
    EventlogPartitiebeheer eventlogPartitiebeheer;

    @Inject
    EventPartitieRepository eventPartitieRepository;

    @Inject
    TaakWorker taakWorker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
            TaakFixtures.verwijderBevestigingen(entityManager);
            TaakFixtures.verwijderWebhookposities(entityManager);
        });
    }

    @AfterEach
    void herstelRegister() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetMaxCursorleeftijd(entityManager,
                NotificatieFixtures.DV_ID, null));
    }

    // Zeven verlopen notificaties bij batches van twee en hoogstens drie batches: zes in de eerste
    // ronde, de zevende in de volgende. Een recente met en een zonder eindstatus blijven staan.
    @Test
    void onderhoud_naDeBewaartermijn_verwijdertNotificatiesEnPogingenInBatches() {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        NotificatieFixtures.plantNotificaties(entityManager, 7, nu.minusDays(400), NotificatieStatus.NIET_BEZORGBAAR, 2);
        UUID recent = UUID.randomUUID();
        UUID lopend = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.voegNotificatieToe(entityManager, recent, NotificatieStatus.NIET_BEZORGBAAR, nu.minusDays(364));
            NotificatieFixtures.voegNotificatieToe(entityManager, lopend, NotificatieStatus.VERZONDEN, null);
            NotificatieFixtures.voegPogingToe(entityManager, lopend, 1, UUID.randomUUID(),
                    PogingStatus.VERZONDEN, nu.minusDays(400));
        });

        onderhoud();

        assertEquals(3, aantal(notificatieRepository::count));
        assertEquals(3, aantal(pogingRepository::count), "twee van de laatste verlopen en die van de lopende");

        onderhoud();

        assertEquals(2, aantal(notificatieRepository::count));
        assertEquals(1, aantal(pogingRepository::count));
        assertTrue(QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findByIdOptional(recent)).isPresent());
        assertTrue(QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findByIdOptional(lopend)).isPresent());
    }

    // Een KEK-rotatie wrapt alleen de sleutel opnieuw: de versleutelde gegevens blijven byte voor byte
    // gelijk en gaan open onder de nieuwe versie. Een sleutel die niet onder zijn versie open gaat,
    // blijft staan zonder de andere tegen te houden.
    @Test
    void onderhoud_sleutelsOnderOudereKekVersie_herwraptZeZonderHerversleuteling() {
        Sleutelbeheer versie1 = SleutelbeheerTest.sleutelbeheer(1, Map.of(1, TEST_KEK_1));
        List<UUID> ids = List.of(opgeslagen(versie1), opgeslagen(versie1), opgeslagen(versie1));
        UUID kapot = opgeslagen(SleutelbeheerTest.sleutelbeheer(1, Map.of(1, SleutelbeheerTest.KEK_1)));
        Map<UUID, VersleuteldeGegevens> voor = ids.stream()
                .collect(Collectors.toMap(id -> id, id -> notificatie(id).getVersleuteldeGegevens()));

        onderhoud();

        for (UUID id : ids) {
            VersleuteldeGegevens na = notificatie(id).getVersleuteldeGegevens();
            assertEquals(2, na.kekVersie());
            assertArrayEquals(voor.get(id).ontvangerVersleuteld(), na.ontvangerVersleuteld());
            assertArrayEquals(voor.get(id).personalisationVersleuteld(), na.personalisationVersleuteld());
            assertFalse(Arrays.equals(voor.get(id).sleutelGewrapt(), na.sleutelGewrapt()));
            assertEquals(ONTVANGER, sleutelbeheer.ontsleutelOntvanger(id, na));
            assertEquals(Map.of("naam", "Voorbeeld BV"), sleutelbeheer.ontsleutelPersonalisation(id, na));
        }

        assertEquals(1, notificatie(kapot).getVersleuteldeGegevens().kekVersie());
    }

    @Test
    void onderhoud_viaDeWorker_plantZichzelfOpnieuw() {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(TaakSoort.ONDERHOUD, null, null, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.ONDERHOUD));

        Taak taak = QuarkusTransaction.requiringNew().call(() -> taakRepository.zoek(TaakSoort.ONDERHOUD, id)).orElseThrow();
        assertTrue(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(59)));
        assertEquals(0, taak.getPogingen());
    }

    @Test
    void maakVolgendeAan_lopendePartitieVoor80ProcentGevuld_maaktDeAansluitendeAan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.van() + 40);

        Optional<EventPartitie> volgende = eventlogPartitiebeheer.maakVolgendeAan();

        assertEquals(Optional.of(EventPartitie.vanaf(lopend.tot(), lopend.tot() + 50)), volgende);
        assertTrue(partities().contains(volgende.get()));
        assertEquals(Optional.empty(), eventlogPartitiebeheer.maakVolgendeAan(), "de nieuwe is nog leeg");
        assertEquals(lopend.naam(), partitieVan(nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC))),
                "een nieuw event landt in een bereikpartitie, niet in de default");
    }

    // De partitie wordt los aangemaakt en aangehangen; elke index van event moet daarbij een deel op
    // de nieuwe partitie krijgen, anders mist de feed of het opruimen er een.
    @Test
    void maakVolgendeAan_nieuwePartitie_heeftElkeIndexVanHetEventlog() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.van() + 40);

        EventPartitie volgende = eventlogPartitiebeheer.maakVolgendeAan().orElseThrow();

        long opEvent = aantalIndexen("event", false);
        assertTrue(opEvent >= 3, "primary key, notificatie/volgnummer en feed");
        assertEquals(opEvent, aantalIndexen(volgende.naam(), true));
    }

    @Test
    void ruimOp_oudePartitieZonderCursor_verwijdertHaar() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertFalse(partities().contains(oud.partitie()));
        assertEquals(0, events(oud.notificatieId()));
    }

    // Liep het onderhoud achter, dan staan er events in de default-partitie onder een bereikpartitie. Die
    // partitie blijft tot de default-partitie daar is opgeruimd; anders leest een vervallen cursor over het gat.
    @Test
    void ruimOp_oudereEventsInDeStandaardpartitie_houdenDePartitieErbovenTegen() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.tot());
        UUID tussen = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        assertEquals("event_standaard", partitieVan(tussen));
        EventPartitie partitie = eventlogPartitiebeheer.maakVolgendeAan().orElseThrow();
        verbruikTransactieIdsTot(partitie.van());
        UUID inPartitie = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        verbruikTransactieIdsTot(partitie.tot());
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verwijderEventsOnder(entityManager, lopend.tot()));

        assertFalse(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(partitie),
                "de partitie blijft zolang de default-partitie er oudere events onder heeft");
        assertEquals(1, events(tussen));

        eventlogPartitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100);
        assertEquals(0, events(tussen));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(partitie));
        assertEquals(0, events(inPartitie));
    }

    @Test
    void ruimOp_jongsteEventBinnenDeBewaartermijn_laatDePartitieStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(364));

        assertFalse(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_geldigeBevestigingInDePartitie_laatHaarStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(29));

        assertFalse(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_bevestigingOuderDanDeMaximaleCursorleeftijd_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    @Test
    void ruimOp_eigenCursorleeftijdInHetRegister_gaatVoorDeDefault() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetMaxCursorleeftijd(entityManager,
                NotificatieFixtures.DV_ID, Duration.ofDays(90)));

        assertFalse(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_bevestigingUitEenAnderEpoch_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(0, oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    // Een cursor achter de partitie heeft haar events al gelezen.
    @Test
    void ruimOp_bevestigingBovenDePartitie_houdtHaarNietTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.partitie().tot(), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    @Test
    void ruimOp_geldigeLeverpositieInDePartitie_laatHaarStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        leverpositie(oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), OffsetDateTime.now(ZoneOffset.UTC));

        assertFalse(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    // Een webhook die steeds faalt werkt bijgewerkt_op bij; de leeftijd telt vanaf de laatste levering.
    @Test
    void ruimOp_leverpositieLangNietVerschoven_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        leverpositie(oud.transactieId(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    // Wat buiten de bereiken valt (de onderhoudstaak liep achter), gaat per rij weg uit de default-partitie.
    @Test
    void ruimStandaardpartitieOp_oudeEventsZonderCursor_verwijdertAlleenDie() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        UUID jong = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC));
        assertEquals("event_standaard", partitieVan(oud));
        // De lege, oudere bereikpartities gaan eerst weg; pas dan komen de rijen erboven aan de beurt.
        eventlogPartitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC));

        eventlogPartitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100);

        assertEquals(0, events(oud));
        assertEquals(1, events(jong));
    }

    // Zolang een oudere bereikpartitie staat (bijvoorbeeld omdat het verwijderen op de lock-timeout
    // stuitte), blijven de rijen erboven: anders leest een verlopen cursor over het gat heen.
    @Test
    void ruimStandaardpartitieOp_oudereBereikpartitieStaatNog_laatDeRijenErbovenStaan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        assertEquals("event_standaard", partitieVan(oud));

        eventlogPartitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100);

        assertEquals(1, events(oud));
    }

    @Test
    void ruimStandaardpartitieOp_geldigeBevestigingOnderDeEvents_laatZeStaan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, transactieIdVan(oud), OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(0, eventlogPartitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100));

        assertEquals(1, events(oud));
    }

    private void onderhoud() {
        onderhoudTaakHandler.voerUit(new Taak(TaakSoort.ONDERHOUD, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null),
                () -> {
                });
    }

    private UUID opgeslagen(Sleutelbeheer beheer) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            notificatie.bewaarVersleuteldeGegevens(beheer.versleutel(notificatie.getId(), ONTVANGER,
                    Map.of("naam", "Voorbeeld BV")));
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    /** Een partitie die de volgende transactie-id bevat, zonder dat er een aansluitende nodig is. */
    private EventPartitie zorgVoorLopendePartitie() {
        for (int i = 0; i < 5 && eventlogPartitiebeheer.maakVolgendeAan().isPresent(); i++) {
            // Tot er een partitie is die nog niet voor 80% gevuld is.
        }

        return partities().getLast();
    }

    /**
     * Een partitie met alleen de events van één notificatie op {@code tijdstip}, helemaal onder het
     * watermerk, als oudste bereikpartitie: de events in oudere partities zijn weggehaald.
     */
    private OudePartitie oudePartitie(OffsetDateTime tijdstip) {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikTransactieIdsTot(lopend.tot());
        EventPartitie partitie = eventlogPartitiebeheer.maakVolgendeAan().orElseThrow();
        verbruikTransactieIdsTot(partitie.van());
        UUID notificatieId = nieuwEvent(tijdstip);
        assertEquals(partitie.naam(), partitieVan(notificatieId));
        verbruikTransactieIdsTot(partitie.tot());
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verwijderEventsOnder(entityManager, partitie.van()));

        return new OudePartitie(partitie, notificatieId, transactieIdVan(notificatieId));
    }

    private UUID nieuwEvent(OffsetDateTime tijdstip) {
        UUID notificatieId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.voegEventToe(entityManager, notificatieId, 0,
                null, NotificatieStatus.AANGENOMEN, tijdstip));

        return notificatieId;
    }

    /** Het aantal indexen op de tabel; voor een partitie alleen de indexen die aan een index van event hangen. */
    private long aantalIndexen(String tabel, boolean aangehangen) {
        String sql = "SELECT count(*) FROM pg_index x WHERE x.indrelid = CAST(?1 AS regclass)"
                + (aangehangen ? " AND EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = x.indexrelid)" : "");

        return QuarkusTransaction.requiringNew().call(() -> ((Number) entityManager.createNativeQuery(sql)
                .setParameter(1, tabel).getSingleResult()).longValue());
    }

    private void verbruikTransactieIdsTot(long transactieId) {
        while (QuarkusTransaction.requiringNew().call(eventPartitieRepository::volgendeTransactieId) < transactieId) {
            QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verbruikTransactieId(entityManager));
        }
    }

    private List<EventPartitie> partities() {
        return QuarkusTransaction.requiringNew().call(eventPartitieRepository::bereikpartities);
    }

    private String partitieVan(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> NotificatieFixtures.partitieVan(entityManager, notificatieId));
    }

    private long transactieIdVan(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> NotificatieFixtures.transactieIdVan(entityManager, notificatieId));
    }

    private void bevestiging(int epoch, long transactieId, OffsetDateTime bevestigdOp) {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.voegBevestigingToe(entityManager, NotificatieFixtures.DV_ID,
                epoch, transactieId, bevestigdOp));
    }

    private void leverpositie(long transactieId, OffsetDateTime geleverdOp, OffsetDateTime bijgewerktOp) {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.voegWebhookpositieToe(entityManager,
                NotificatieFixtures.DV_ID, transactieId, geleverdOp, bijgewerktOp));
    }

    private long events(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> NotificatieFixtures.telEvents(entityManager, notificatieId));
    }

    private static long aantal(Supplier<Long> teller) {
        return QuarkusTransaction.requiringNew().call(teller::get);
    }

    private record OudePartitie(EventPartitie partitie, UUID notificatieId, long transactieId) {
    }
}
