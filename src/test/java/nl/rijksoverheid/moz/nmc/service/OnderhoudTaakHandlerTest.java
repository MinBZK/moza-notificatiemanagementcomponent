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
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventPartitie;
import nl.rijksoverheid.moz.nmc.repository.EventPartitieRepository;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    Partitiebeheer partitiebeheer;

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
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
            sql("DELETE FROM bevestiging");
            sql("DELETE FROM webhookpositie");
        });
    }

    @AfterEach
    void herstelRegister() {
        QuarkusTransaction.requiringNew().run(() -> sql("UPDATE dienstverlener SET max_cursorleeftijd = NULL"));
    }

    // Zeven verlopen notificaties bij batches van twee en hoogstens drie batches: zes in de eerste
    // ronde, de zevende in de volgende. Een recente terminale en een niet-terminale blijven staan.
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

        assertEquals(3, tel("SELECT count(*) FROM notificatie"));
        assertEquals(3, tel("SELECT count(*) FROM poging"), "twee van de laatste verlopen en die van de lopende");

        onderhoud();

        assertEquals(2, tel("SELECT count(*) FROM notificatie"));
        assertEquals(1, tel("SELECT count(*) FROM poging"));
        assertEquals(1, tel("SELECT count(*) FROM notificatie WHERE id = '" + recent + "'"));
        assertEquals(1, tel("SELECT count(*) FROM notificatie WHERE id = '" + lopend + "'"));
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

        assertEquals(1, taakWorker.verwerk(TaakSoort.ONDERHOUD));

        Taak taak = QuarkusTransaction.requiringNew().call(() -> taakRepository.zoek(TaakSoort.ONDERHOUD, id)).orElseThrow();
        assertTrue(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(59)));
        assertEquals(0, taak.getPogingen());
    }

    @Test
    void maakVolgendeAan_lopendePartitieVoor80ProcentGevuld_maaktDeAansluitendeAan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikXidsTot(lopend.van() + 40);

        Optional<EventPartitie> volgende = partitiebeheer.maakVolgendeAan();

        assertEquals(Optional.of(EventPartitie.vanaf(lopend.tot(), lopend.tot() + 50)), volgende);
        assertTrue(partities().contains(volgende.get()));
        assertEquals(Optional.empty(), partitiebeheer.maakVolgendeAan(), "de nieuwe is nog leeg");
        assertEquals(lopend.naam(), partitieVan(nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC))),
                "een nieuw event landt in een bereikpartitie, niet in de default");
    }

    @Test
    void ruimOp_oudePartitieZonderCursor_verwijdertHaar() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));

        assertTrue(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertFalse(partities().contains(oud.partitie()));
        assertEquals(0, tel("SELECT count(*) FROM event WHERE notificatie_id = '" + oud.notificatieId() + "'"));
    }

    @Test
    void ruimOp_jongsteEventBinnenDeBewaartermijn_laatDePartitieStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(364));

        assertFalse(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_geldigeBevestigingInDePartitie_laatHaarStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.xid(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(29));

        assertFalse(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_bevestigingOuderDanDeMaximaleCursorleeftijd_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.xid(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        assertTrue(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    @Test
    void ruimOp_eigenCursorleeftijdInHetRegister_gaatVoorDeDefault() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.xid(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        QuarkusTransaction.requiringNew().run(() -> sql("UPDATE dienstverlener SET max_cursorleeftijd = interval '90 days'"));

        assertFalse(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    @Test
    void ruimOp_bevestigingUitEenAnderEpoch_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(0, oud.xid(), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    // Een cursor achter de partitie heeft haar events al gelezen.
    @Test
    void ruimOp_bevestigingBovenDePartitie_houdtHaarNietTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, oud.partitie().tot(), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    @Test
    void ruimOp_geldigeLeverpositieInDePartitie_laatHaarStaan() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        leverpositie(oud.xid(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), OffsetDateTime.now(ZoneOffset.UTC));

        assertFalse(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));

        assertTrue(partities().contains(oud.partitie()));
    }

    // Een webhook die steeds faalt werkt bijgewerkt_op bij; de leeftijd telt vanaf de laatste levering.
    @Test
    void ruimOp_leverpositieLangNietVerschoven_houdtNietsTegen() {
        OudePartitie oud = oudePartitie(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        leverpositie(oud.xid(), OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC)).contains(oud.partitie()));
    }

    // Wat buiten de bereiken valt (de onderhoudstaak liep achter), gaat per rij weg uit de default-partitie.
    @Test
    void ruimStandaardpartitieOp_oudeEventsZonderCursor_verwijdertAlleenDie() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikXidsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        UUID jong = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC));
        assertEquals("event_standaard", partitieVan(oud));
        // De lege, oudere bereikpartities gaan eerst weg; pas dan komen de rijen erboven aan de beurt.
        partitiebeheer.ruimOp(OffsetDateTime.now(ZoneOffset.UTC));

        partitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100);

        assertEquals(0, tel("SELECT count(*) FROM event WHERE notificatie_id = '" + oud + "'"));
        assertEquals(1, tel("SELECT count(*) FROM event WHERE notificatie_id = '" + jong + "'"));
    }

    // Zolang een oudere bereikpartitie staat (bijvoorbeeld omdat het verwijderen op de lock-timeout
    // stuitte), blijven de rijen erboven: anders leest een verlopen cursor over het gat heen.
    @Test
    void ruimStandaardpartitieOp_oudereBereikpartitieStaatNog_laatDeRijenErbovenStaan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikXidsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        assertEquals("event_standaard", partitieVan(oud));

        partitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100);

        assertEquals(1, tel("SELECT count(*) FROM event WHERE notificatie_id = '" + oud + "'"));
    }

    @Test
    void ruimStandaardpartitieOp_geldigeBevestigingOnderDeEvents_laatZeStaan() {
        EventPartitie lopend = zorgVoorLopendePartitie();
        verbruikXidsTot(lopend.tot());
        UUID oud = nieuwEvent(OffsetDateTime.now(ZoneOffset.UTC).minusDays(400));
        bevestiging(1, xidVan(oud), OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(0, partitiebeheer.ruimStandaardpartitieOp(OffsetDateTime.now(ZoneOffset.UTC), 100));

        assertEquals(1, tel("SELECT count(*) FROM event WHERE notificatie_id = '" + oud + "'"));
    }

    @Test
    void migratie_laatsteStatusKolommenBestaanNietMeer() {
        assertEquals(0, tel("SELECT count(*) FROM information_schema.columns WHERE table_name = 'notificatie' "
                + "AND column_name IN ('laatste_status', 'laatste_status_update')"));
    }

    @Test
    void databaserol_heeftDeDrieTimeouts() {
        List<String> instellingen = QuarkusTransaction.requiringNew().call(() -> entityManager.createNativeQuery("""
                        SELECT unnest(s.setconfig)
                          FROM pg_db_role_setting s
                          JOIN pg_roles r ON r.oid = s.setrole
                          JOIN pg_database d ON d.oid = s.setdatabase
                         WHERE r.rolname = current_user AND d.datname = current_database()
                        """, String.class).getResultList());

        assertTrue(instellingen.containsAll(List.of("statement_timeout=30s", "transaction_timeout=1min",
                "idle_in_transaction_session_timeout=30s")), instellingen.toString());
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
        for (int i = 0; i < 5 && partitiebeheer.maakVolgendeAan().isPresent(); i++) {
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
        verbruikXidsTot(lopend.tot());
        EventPartitie partitie = partitiebeheer.maakVolgendeAan().orElseThrow();
        verbruikXidsTot(partitie.van());
        UUID notificatieId = nieuwEvent(tijdstip);
        assertEquals(partitie.naam(), partitieVan(notificatieId));
        verbruikXidsTot(partitie.tot());
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("DELETE FROM event WHERE xid < CAST(CAST(?1 AS text) AS xid8)")
                .setParameter(1, partitie.van())
                .executeUpdate());

        return new OudePartitie(partitie, notificatieId, xidVan(notificatieId));
    }

    private UUID nieuwEvent(OffsetDateTime tijdstip) {
        UUID notificatieId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.voegEventToe(entityManager, notificatieId, 0,
                null, NotificatieStatus.AANGENOMEN, tijdstip));

        return notificatieId;
    }

    // Elke transactie die een xid vraagt, verbruikt er een.
    private void verbruikXidsTot(long xid) {
        while (QuarkusTransaction.requiringNew().call(eventPartitieRepository::volgendeXid) < xid) {
            QuarkusTransaction.requiringNew().run(() -> entityManager
                    .createNativeQuery("SELECT pg_current_xact_id()::text").getSingleResult());
        }
    }

    private List<EventPartitie> partities() {
        return QuarkusTransaction.requiringNew().call(eventPartitieRepository::bereikpartities);
    }

    private String partitieVan(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> (String) entityManager
                .createNativeQuery("SELECT tableoid::regclass::text FROM event WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult());
    }

    private long xidVan(UUID notificatieId) {
        return tel("SELECT xid::text::bigint FROM event WHERE notificatie_id = '" + notificatieId + "'");
    }

    private void bevestiging(int epoch, long xid, OffsetDateTime bevestigdOp) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery("""
                        INSERT INTO bevestiging (dv_id, epoch, xid, event_id, bevestigd_op)
                        VALUES (?1, ?2, CAST(CAST(?3 AS text) AS xid8), 1, ?4)
                        """)
                .setParameter(1, NotificatieFixtures.DV_ID)
                .setParameter(2, epoch)
                .setParameter(3, xid)
                .setParameter(4, bevestigdOp)
                .executeUpdate());
    }

    private void leverpositie(long xid, OffsetDateTime geleverdOp, OffsetDateTime bijgewerktOp) {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery("""
                        INSERT INTO webhookpositie (dv_id, epoch, xid, event_id, mislukkingen, bijgewerkt_op, geleverd_op)
                        VALUES (?1, 1, CAST(CAST(?2 AS text) AS xid8), 1, 3, ?3, ?4)
                        """)
                .setParameter(1, NotificatieFixtures.DV_ID)
                .setParameter(2, xid)
                .setParameter(3, bijgewerktOp)
                .setParameter(4, geleverdOp)
                .executeUpdate());
    }

    private long tel(String sql) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
    }

    private void sql(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    private record OudePartitie(EventPartitie partitie, UUID notificatieId, long xid) {
    }
}
