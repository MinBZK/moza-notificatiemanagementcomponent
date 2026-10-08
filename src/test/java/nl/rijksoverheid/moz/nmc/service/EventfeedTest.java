package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: nmc.feed.max-pagina=10, nmc.feed.cluster-epoch=1.
@QuarkusTest
class EventfeedTest {

    private static final UUID TWEEDE_DV = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Inject
    Eventfeed eventfeed;

    @Inject
    EventRepository eventRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    Clock klok;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            entityManager.createNativeQuery("DELETE FROM bevestiging").executeUpdate();
            entityManager.createNativeQuery("INSERT INTO dienstverlener (id, oin, naam) VALUES (?1, '00000000000000000002', 'Tweede') "
                    + "ON CONFLICT (id) DO NOTHING").setParameter(1, TWEEDE_DV).executeUpdate();
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
        assertNull(eventfeed.lees(TWEEDE_DV, null, null).cursor(), "zonder events en zonder cursor blijft de cursor leeg");
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
            Future<Long> laat = openTransactie.submit(() -> QuarkusTransaction.requiringNew().call(() -> {
                long id = voegEventToe(NotificatieFixtures.DV_ID);
                geschreven.countDown();
                commit.await(20, TimeUnit.SECONDS);

                return id;
            }));
            assertTrue(geschreven.await(10, TimeUnit.SECONDS));
            long na = schrijfEvent(NotificatieFixtures.DV_ID);

            EventPagina tijdensOpen = eventfeed.lees(NotificatieFixtures.DV_ID, null, null);
            assertEquals(List.of(voor), ids(tijdensOpen), "het event na de open transactie committet al, maar valt buiten het watermerk");

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

        assertEquals(10, eventfeed.lees(NotificatieFixtures.DV_ID, null, 50).events().size());
        assertEquals(3, eventfeed.lees(NotificatieFixtures.DV_ID, null, 3).events().size());
        assertThrows(IllegalArgumentException.class, () -> eventfeed.lees(NotificatieFixtures.DV_ID, null, 0));
    }

    @Test
    void lees_cursorUitEenAnderEpoch_isVervallen() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(CursorVervallenException.class, () -> eventfeed.lees(NotificatieFixtures.DV_ID, new Cursor(2, 1, 1), null));
    }

    // Het log wordt per partitie opgeruimd; een cursor onder het oudste event kan events gemist hebben.
    @Test
    void lees_cursorOnderHetOudsteEvent_isVervallen() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(CursorVervallenException.class, () -> eventfeed.lees(NotificatieFixtures.DV_ID, new Cursor(1, 1, 1), null));
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

        Bevestiging bevestiging = eventfeed.bevestiging(NotificatieFixtures.DV_ID).orElseThrow();
        assertEquals(tweede, bevestiging.cursor());
        assertTrue(eventfeed.bevestiging(TWEEDE_DV).isEmpty());
    }

    @Test
    void bevestig_vervallenCursor_weigert() {
        schrijfEvent(NotificatieFixtures.DV_ID);

        assertThrows(CursorVervallenException.class, () -> eventfeed.bevestig(NotificatieFixtures.DV_ID, new Cursor(2, 1, 1)));
    }

    @Test
    void constructor_ongeldigeConfiguratie_weigert() {
        assertThrows(IllegalStateException.class, () -> new Eventfeed(eventRepository, null, klok, -1, 10));
        assertThrows(IllegalStateException.class, () -> new Eventfeed(eventRepository, null, klok, 1, 0));
    }

    private long schrijfEvent(UUID dvId) {
        return QuarkusTransaction.requiringNew().call(() -> voegEventToe(dvId));
    }

    // Het eventlog heeft geen foreign key naar notificatie, dus een los event volstaat.
    private long voegEventToe(UUID dvId) {
        return ((Number) entityManager.createNativeQuery("INSERT INTO event (tijdstip, dv_id, notificatie_id, volgnummer, naar) "
                        + "VALUES (now(), ?1, ?2, 0, 'AANGENOMEN') RETURNING id")
                .setParameter(1, dvId)
                .setParameter(2, UUID.randomUUID())
                .getSingleResult()).longValue();
    }

    private static List<Long> ids(EventPagina pagina) {
        List<Long> ids = new ArrayList<>();
        pagina.events().stream().map(Event::getId).forEach(ids::add);

        return ids;
    }
}
