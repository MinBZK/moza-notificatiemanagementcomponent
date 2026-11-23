package nl.rijksoverheid.moz.nmc.service.onderhoud;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import nl.rijksoverheid.moz.nmc.repository.EventPartitieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Toetst {@link EventlogPartitiebeheer} tegen de database: het aanmaken van de volgende partitie en het
 * opruimen van bereikpartities en de default-partitie. {@code %test}: bewaartermijn afleverbewijs 365
 * dagen, maximale cursorleeftijd 30 dagen, partities van 50 transactie-ids, cluster-epoch 1.
 */
@QuarkusTest
class EventlogPartitiebeheerIntegratieTest {

    @Inject
    EventlogPartitiebeheer eventlogPartitiebeheer;

    @Inject
    EventPartitieRepository eventPartitieRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @AfterEach
    void herstelRegister() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetMaxCursorleeftijd(entityManager,
                NotificatieFixtures.DV_ID, null));
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

    /** Een partitie die de volgende transactie-id bevat, zonder dat er een aansluitende nodig is. */
    // Een bevestiging die commit tussen de toets zonder vergrendeling en die eronder, houdt de partitie
    // alsnog tegen; de partities erboven komen dan niet meer aan de beurt.
    @Test
    void ruimOp_bevestigingTussenDeToetsEnDeVergrendeling_laatDePartitieStaanEnStopt() {
        EventPartitieRepository repository = mock(EventPartitieRepository.class);
        EventPartitie oudste = new EventPartitie("event_1", 0, 50);
        when(repository.bereikpartities()).thenReturn(List.of(oudste, new EventPartitie("event_2", 50, 100)));
        when(repository.watermerk()).thenReturn(1000L);
        when(repository.oudsteBeschermdeTransactieId(anyInt(), any(), any()))
                .thenReturn(Optional.empty(), Optional.of(10L));
        when(repository.registratieVanJongsteEvent(oudste)).thenReturn(Optional.empty());
        EventlogPartitiebeheer beheer = new EventlogPartitiebeheer(repository,
                TestConfig.opruimtermijnen(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3)),
                50, 5, Duration.ofMillis(500), TestConfig.feed());

        assertTrue(beheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).isEmpty());

        verify(repository).vergrendelEventlog();
        verify(repository, times(2)).oudsteBeschermdeTransactieId(anyInt(), any(), any());
        verify(repository, never()).verwijder(any());
    }

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

    private record OudePartitie(EventPartitie partitie, UUID notificatieId, long transactieId) {
    }
}
