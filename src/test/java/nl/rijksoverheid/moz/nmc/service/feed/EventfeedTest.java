package nl.rijksoverheid.moz.nmc.service.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.repository.BevestigingRepository;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// %test: nmc.feed.max-pagina=10, nmc.feed.cluster-epoch=1.
@QuarkusTest
class EventfeedTest {

    private static final UUID TWEEDE_DV = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Inject
    Eventfeed eventfeed;

    @Inject
    EventRepository eventRepository;

    @Inject
    BevestigingRepository bevestigingRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    Clock clock;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
        QuarkusTransaction.requiringNew().run(() -> {
            TaakFixtures.voegDienstverlenerToe(entityManager, TWEEDE_DV, "00000000000000000002", "Tweede");
        });
    }

    @Test
    void lees_zonderCursor_geeftDeEventsInCommitvolgordeMetEenCursorNaHetLaatste() {
        long eerste = schrijfEvent(NotificatieFixtures.DV_ID);
        long tweede = schrijfEvent(NotificatieFixtures.DV_ID);

        EventPagina pagina = eventfeed.lees(NotificatieFixtures.DV_ID, null, null);

        assertEquals(List.of(eerste, tweede), ids(pagina));
        assertEquals(tweede, pagina.cursor().eventId());
        assertEquals(1, pagina.cursor().epoch());
    }

    @Test
    void lees_vanafDeCursor_geeftAlleenWatDaarnaKomt_enEenLegePaginaHoudtDeCursor() {
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor na = eventfeed.lees(NotificatieFixtures.DV_ID, null, null).cursor();
        long derde = schrijfEvent(NotificatieFixtures.DV_ID);

        EventPagina vervolg = eventfeed.lees(NotificatieFixtures.DV_ID, na, null);
        EventPagina leeg = eventfeed.lees(NotificatieFixtures.DV_ID, vervolg.cursor(), null);

        assertEquals(List.of(derde), ids(vervolg));
        assertEquals(List.of(), leeg.events());
        assertEquals(vervolg.cursor(), leeg.cursor());
        assertNull(
                eventfeed.lees(TWEEDE_DV, null, null).cursor(), "zonder events en zonder cursor blijft de cursor leeg");
    }

    @Test
    void lees_tweeDienstverleners_zienElkaarsEventsNiet() {
        long eigen = schrijfEvent(NotificatieFixtures.DV_ID);
        long ander = schrijfEvent(TWEEDE_DV);

        assertEquals(List.of(eigen), ids(eventfeed.lees(NotificatieFixtures.DV_ID, null, null)));
        assertEquals(List.of(ander), ids(eventfeed.lees(TWEEDE_DV, null, null)));
    }

    // Het watermerk: een event uit een transactie die nog loopt, en elk event dat daarna committet,
    // blijft buiten de pagina. Zo slaat een cursor nooit een event over dat later committet dan een
    // event met een hogere transactie-id.
    @Test
    void lees_eenOpenTransactie_houdtLaterGecommitteEventsTegenTotZeCommit() throws Exception {
        long voor = schrijfEvent(NotificatieFixtures.DV_ID);
        CountDownLatch geschreven = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService openTransactie = Executors.newSingleThreadExecutor();

        try {
            Future<Long> laat = openTransactie.submit(() -> QuarkusTransaction.requiringNew()
                    .call(() -> {
                        long id = voegEventToe(NotificatieFixtures.DV_ID);
                        geschreven.countDown();
                        commit.await(20, TimeUnit.SECONDS);

                        return id;
                    }));
            assertTrue(geschreven.await(10, TimeUnit.SECONDS));
            long na = schrijfEvent(NotificatieFixtures.DV_ID);

            EventPagina tijdensOpen = eventfeed.lees(NotificatieFixtures.DV_ID, null, null);
            assertEquals(
                    List.of(voor),
                    ids(tijdensOpen),
                    "het event na de open transactie committet al, maar valt buiten het watermerk");

            commit.countDown();
            long laatGecommit = laat.get(20, TimeUnit.SECONDS);

            EventPagina daarna = eventfeed.lees(NotificatieFixtures.DV_ID, tijdensOpen.cursor(), null);
            assertEquals(List.of(laatGecommit, na), ids(daarna), "niets overgeslagen, in commitpositie");
        } finally {
            commit.countDown();
            openTransactie.shutdownNow();
        }
    }

    @Test
    void lees_paginagrootte_isBegrensdOpHetMaximum() {
        for (int i = 0; i < 12; i++) {
            schrijfEvent(NotificatieFixtures.DV_ID);
        }

        assertEquals(
                10, eventfeed.lees(NotificatieFixtures.DV_ID, null, 50).events().size());
        assertEquals(
                3, eventfeed.lees(NotificatieFixtures.DV_ID, null, 3).events().size());
        assertThrows(IllegalArgumentException.class, () -> eventfeed.lees(NotificatieFixtures.DV_ID, null, 0));
    }

    // Events uit één transactie delen hun transactie-id. Eindigt een pagina er middenin, dan moet de
    // volgende lezing met de rest van die transactie verdergaan; op transactie-id alleen valt die weg.
    @Test
    void lees_paginagrensMiddenInEenTransactie_geeftElkEventPreciesEenKeer() {
        List<Long> geschreven = QuarkusTransaction.requiringNew()
                .call(() -> List.of(
                        voegEventToe(NotificatieFixtures.DV_ID),
                        voegEventToe(NotificatieFixtures.DV_ID),
                        voegEventToe(NotificatieFixtures.DV_ID)));

        EventPagina eerste = eventfeed.lees(NotificatieFixtures.DV_ID, null, 2);
        EventPagina tweede = eventfeed.lees(NotificatieFixtures.DV_ID, eerste.cursor(), 2);
        EventPagina derde = eventfeed.lees(NotificatieFixtures.DV_ID, tweede.cursor(), 2);

        assertEquals(geschreven.subList(0, 2), ids(eerste));
        assertEquals(geschreven.subList(2, 3), ids(tweede));
        assertTrue(derde.events().isEmpty());
    }

    @Test
    void lees_cursorUitEenAnderEpoch_isVervallen() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(
                CursorVervallenException.class,
                () -> eventfeed.lees(NotificatieFixtures.DV_ID, new Cursor(2, 1, 1), null));
    }

    // Het log wordt per partitie opgeruimd; een cursor onder het oudste event kan events gemist hebben.
    @Test
    void lees_cursorOnderHetOudsteEvent_isVervallen() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(
                CursorVervallenException.class,
                () -> eventfeed.lees(NotificatieFixtures.DV_ID, new Cursor(1, 1, 1), null));
    }

    // Na een herstel uit back-up zegt een oude positie niets meer; de webhook begint opnieuw.
    @Test
    void leesVoorWebhook_positieUitEenAnderEpoch_begintBijHetOudsteEvent() {
        long eerste = schrijfEvent(NotificatieFixtures.DV_ID);
        long tweede = schrijfEvent(NotificatieFixtures.DV_ID);

        EventPagina pagina = eventfeed.leesVoorWebhook(NotificatieFixtures.DV_ID, new Cursor(2, Long.MAX_VALUE, 1), 50);

        assertEquals(List.of(eerste, tweede), ids(pagina));
        assertEquals(1, pagina.cursor().epoch());
    }

    // Onder het oudste event is alles al opgeruimd: verder lezen geeft geen 410 zoals de feed, maar wel
    // een waarschuwing, want de dienstverlener mist wat ertussen lag.
    @Test
    void leesVoorWebhook_positieOnderHetOudsteEvent_leestVerderMetEenWaarschuwing() {
        long eerste = schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor na;
        List<String> waarschuwingen;
        try (LogVanger vanger = LogVanger.van(Eventfeed.class)) {
            na = eventfeed
                    .leesVoorWebhook(NotificatieFixtures.DV_ID, new Cursor(1, 1, 1), 1)
                    .cursor();
            waarschuwingen = vanger.regelsOpNiveau(Level.WARNING);
        }
        long tweede = schrijfEvent(NotificatieFixtures.DV_ID);

        assertEquals(eerste, na.eventId());
        assertEquals(1, waarschuwingen.size());
        assertTrue(waarschuwingen.getFirst().contains("onder het oudste beschikbare event"));
        assertEquals(List.of(tweede), ids(eventfeed.leesVoorWebhook(NotificatieFixtures.DV_ID, na, 50)));
    }

    // De registratiepositie van een webhook: na het jongste event, of het begin van een leeg log.
    @Test
    void kopVanHetLog_isHetJongsteEventOfHetBeginVanEenLeegLog() {
        assertEquals(new Cursor(1, 0, 0), eventfeed.kopVanHetLog());

        schrijfEvent(NotificatieFixtures.DV_ID);
        long jongste = schrijfEvent(TWEEDE_DV);

        Cursor kop = eventfeed.kopVanHetLog();
        assertEquals(jongste, kop.eventId());
        assertTrue(ids(eventfeed.leesVoorWebhook(NotificatieFixtures.DV_ID, kop, 50))
                .isEmpty());
    }

    @Test
    void bevestig_gaatAlleenVooruit() {
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor eerste = eventfeed.lees(NotificatieFixtures.DV_ID, null, null).cursor();
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor tweede = eventfeed.lees(NotificatieFixtures.DV_ID, eerste, null).cursor();

        assertTrue(eventfeed.bevestig(NotificatieFixtures.DV_ID, eerste));
        assertTrue(eventfeed.bevestig(NotificatieFixtures.DV_ID, tweede));
        assertFalse(eventfeed.bevestig(NotificatieFixtures.DV_ID, eerste), "een oudere cursor zet hem niet terug");

        Bevestiging bevestiging =
                eventfeed.bevestiging(NotificatieFixtures.DV_ID).orElseThrow();
        assertEquals(tweede, bevestiging.getCursor());
        assertTrue(eventfeed.bevestiging(TWEEDE_DV).isEmpty());
    }

    // Het opruimen van het eventlog rekent de cursorleeftijd vanaf dit tijdstip. Een consument die blijft
    // hangen en steeds dezelfde cursor bevestigt, mag het log niet voor altijd vasthouden.
    @Test
    void bevestig_dezelfdeCursorOpnieuw_laatHetTijdstipStaan() {
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor cursor = eventfeed.lees(NotificatieFixtures.DV_ID, null, null).cursor();
        OffsetDateTime eerste = OffsetDateTime.parse("2030-01-01T10:00:00Z");
        OffsetDateTime later = eerste.plusMinutes(1);

        QuarkusTransaction.requiringNew()
                .run(() -> bevestigingRepository.updateCursorPositie(NotificatieFixtures.DV_ID, cursor, eerste));
        boolean gewijzigd = QuarkusTransaction.requiringNew()
                .call(() -> bevestigingRepository.updateCursorPositie(NotificatieFixtures.DV_ID, cursor, later));

        assertFalse(gewijzigd);
        assertEquals(
                eerste.toInstant(),
                eventfeed
                        .bevestiging(NotificatieFixtures.DV_ID)
                        .orElseThrow()
                        .getBevestigdOp()
                        .toInstant());
    }

    @Test
    void bevestig_cursorVerderDanDeBewaarde_zetHetTijdstip() {
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor eersteCursor =
                eventfeed.lees(NotificatieFixtures.DV_ID, null, null).cursor();
        schrijfEvent(NotificatieFixtures.DV_ID);
        Cursor tweedeCursor =
                eventfeed.lees(NotificatieFixtures.DV_ID, eersteCursor, null).cursor();
        OffsetDateTime eerste = OffsetDateTime.parse("2030-01-01T10:00:00Z");
        OffsetDateTime later = eerste.plusMinutes(1);

        QuarkusTransaction.requiringNew()
                .run(() -> bevestigingRepository.updateCursorPositie(NotificatieFixtures.DV_ID, eersteCursor, eerste));
        boolean gewijzigd = QuarkusTransaction.requiringNew()
                .call(() -> bevestigingRepository.updateCursorPositie(NotificatieFixtures.DV_ID, tweedeCursor, later));

        assertTrue(gewijzigd);
        assertEquals(
                later.toInstant(),
                eventfeed
                        .bevestiging(NotificatieFixtures.DV_ID)
                        .orElseThrow()
                        .getBevestigdOp()
                        .toInstant());
    }

    // Een cursor van vóór een herstel uit back-up zonder verhoogd epoch wijst boven elke nieuwe
    // transactie-id; zonder deze controle zou hij lege pagina's geven tot de id's hem inhalen.
    @Test
    void lees_cursorBovenDeJongsteTransactie_isVervallen() {
        long event = schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(
                CursorVervallenException.class,
                () -> eventfeed.lees(NotificatieFixtures.DV_ID, new Cursor(1, 1L << 62, event), null));
    }

    // De grens ligt boven elke gecommitte transactie; een cursor op het laatste event blijft dus geldig.
    @Test
    void bovengrensTransactieId_ligtBovenHetJongsteEvent() {
        long event = schrijfEvent(NotificatieFixtures.DV_ID);

        QuarkusTransaction.requiringNew().run(() -> {
            long transactieId = eventRepository.findById(event).getTransactieId();

            assertTrue(eventRepository.bovengrensTransactieId() > transactieId);
        });
    }

    // Zonder events is er geen oudste event om de cursor aan te toetsen; alleen epoch en bovengrens gelden.
    @Test
    void lees_cursorOpEenLeegLog_geeftEenLegePaginaMetDezelfdeCursor() {
        Cursor cursor = new Cursor(1, 0, 0);

        EventPagina pagina = eventfeed.lees(NotificatieFixtures.DV_ID, cursor, null);

        assertEquals(List.of(), pagina.events());
        assertEquals(cursor, pagina.cursor());
    }

    // Een webhook zonder leverpositie begint bij het oudste beschikbare event.
    @Test
    void leesVoorWebhook_zonderPositie_begintBijHetOudsteEvent() {
        long eerste = schrijfEvent(NotificatieFixtures.DV_ID);
        long tweede = schrijfEvent(NotificatieFixtures.DV_ID);

        EventPagina pagina = eventfeed.leesVoorWebhook(NotificatieFixtures.DV_ID, null, 10);

        assertEquals(List.of(eerste, tweede), ids(pagina));
        assertEquals(tweede, pagina.cursor().eventId());
    }

    @Test
    void bevestig_vervallenCursor_weigert() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(
                CursorVervallenException.class,
                () -> eventfeed.bevestig(NotificatieFixtures.DV_ID, new Cursor(2, 1, 1)));
        assertThrows(
                CursorVervallenException.class,
                () -> eventfeed.bevestig(NotificatieFixtures.DV_ID, new Cursor(1, 1L << 62, 1)));
    }

    private long schrijfEvent(UUID dvId) {
        return QuarkusTransaction.requiringNew().call(() -> voegEventToe(dvId));
    }

    // Het eventlog heeft geen foreign key naar notificatie, dus een los event volstaat.
    private long voegEventToe(UUID dvId) {
        Event event =
                new Event(dvId, UUID.randomUUID(), 0, null, NotificatieStatus.AANGENOMEN, null, OffsetDateTime.now());
        eventRepository.persist(event);

        return event.getId();
    }

    private static List<Long> ids(EventPagina pagina) {
        List<Long> ids = new ArrayList<>();
        pagina.events().stream().map(Event::getId).forEach(ids::add);

        return ids;
    }
}
