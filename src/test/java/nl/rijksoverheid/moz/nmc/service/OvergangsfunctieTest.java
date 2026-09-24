package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionalException;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Overgangsregels;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OvergangsfunctieTest {

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    EventRepository eventRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void neemAan_nieuweNotificatie_staatOpAangenomenMetEventNul() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);
            List<Event> events = eventRepository.findByNotificatie(id);

            assertEquals(NotificatieStatus.AANGENOMEN, herladen.getStatus());
            assertEquals(0, herladen.getVersie());
            assertEquals(1, events.size());
            assertEquals(0, events.getFirst().getVolgnummer());
            assertNull(events.getFirst().getVan());
            assertEquals(NotificatieStatus.AANGENOMEN, events.getFirst().getNaar());
        });
    }

    @Test
    void neemAan_alAangenomen_gooit() {
        UUID id = aangenomenNotificatieMetStatus(NotificatieStatus.AANGENOMEN);

        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                overgangsfunctie.neemAan(notificatieRepository.findById(id))));
    }

    @ParameterizedTest
    @MethodSource("toegestaneOvergangen")
    void voerUit_toegestaneOvergang_verhoogtVersieMetEenEnSchrijftEvent(NotificatieStatus van, NotificatieStatus naar) {
        UUID id = aangenomenNotificatieMetStatus(van);

        OvergangUitkomst uitkomst = QuarkusTransaction.requiringNew().call(() ->
                overgangsfunctie.voerUit(id, naar, Reden.ONBEREIKBAAR));

        assertTrue(uitkomst.isUitgevoerd());
        assertEquals(van, uitkomst.van());

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);
            Event laatste = eventRepository.findByNotificatie(id).getLast();

            assertEquals(naar, herladen.getStatus());
            assertEquals(1, herladen.getVersie());
            assertEquals(1, laatste.getVolgnummer());
            assertEquals(van, laatste.getVan());
            assertEquals(naar, laatste.getNaar());
        });
    }

    @ParameterizedTest
    @MethodSource("nietToegestaneOvergangen")
    void voerUit_nietToegestaneOvergang_wijzigtNiets(NotificatieStatus van, NotificatieStatus naar) {
        UUID id = aangenomenNotificatieMetStatus(van);

        OvergangUitkomst uitkomst = QuarkusTransaction.requiringNew().call(() ->
                overgangsfunctie.voerUit(id, naar, null));

        assertFalse(uitkomst.isUitgevoerd());

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            assertEquals(van, herladen.getStatus());
            assertEquals(0, herladen.getVersie());
            assertEquals(1, eventRepository.findByNotificatie(id).size());
        });
    }

    // Een herverzending wijzigt de status niet; de versie moet toch precies één oplopen.
    @Test
    void voerUit_herverzending_houdtStatusEnReden() {
        UUID id = aangenomenNotificatieMetStatus(NotificatieStatus.VERZONDEN);

        QuarkusTransaction.requiringNew().run(() -> overgangsfunctie.voerUit(id, NotificatieStatus.VERZONDEN, null));

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            assertEquals(NotificatieStatus.VERZONDEN, herladen.getStatus());
            assertNull(herladen.getReden());
            assertEquals(1, herladen.getVersie());
        });
    }

    @Test
    void voerUit_tweeOvergangenInEenTransactie_geeftOpeenvolgendeVolgnummers() {
        UUID id = aangenomenNotificatieMetStatus(NotificatieStatus.AANGENOMEN);

        QuarkusTransaction.requiringNew().run(() -> {
            overgangsfunctie.voerUit(id, NotificatieStatus.IN_VERZENDING, null);
            overgangsfunctie.voerUit(id, NotificatieStatus.VERZONDEN, null);
        });

        QuarkusTransaction.requiringNew().run(() -> {
            List<Long> volgnummers = eventRepository.findByNotificatie(id).stream().map(Event::getVolgnummer).toList();

            assertEquals(List.of(0L, 1L, 2L), volgnummers);
            assertEquals(2, notificatieRepository.findById(id).getVersie());
        });
    }

    @Test
    void voerUit_onbekendeNotificatie_gooitNietGevonden() {
        assertThrows(NotificatieNietGevondenException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                overgangsfunctie.voerUit(UUID.randomUUID(), NotificatieStatus.VERZONDEN, null)));
    }

    @Test
    void voerUit_zonderTransactie_gooit() {
        assertThrows(TransactionalException.class,
                () -> overgangsfunctie.voerUit(UUID.randomUUID(), NotificatieStatus.VERZONDEN, null));
    }

    // De eerste transactie houdt de rijvergrendeling vast tot de tweede staat te wachten; die leest
    // daarna de status van de eerste. Beide overgangen slagen, met opeenvolgende volgnummers.
    @Test
    void voerUit_tweeGelijktijdigeTransacties_wordenGeserialiseerd() throws Exception {
        UUID id = aangenomenNotificatieMetStatus(NotificatieStatus.VERZONDEN);
        CountDownLatch eersteHeeftVergrendeld = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<OvergangUitkomst> eerste = pool.submit(() -> QuarkusTransaction.requiringNew().call(() -> {
                OvergangUitkomst uitkomst = overgangsfunctie.voerUit(id, NotificatieStatus.BEZORGD, null);
                eersteHeeftVergrendeld.countDown();
                Thread.sleep(500);

                return uitkomst;
            }));

            assertTrue(eersteHeeftVergrendeld.await(10, TimeUnit.SECONDS));

            Future<OvergangUitkomst> tweede = pool.submit(() -> QuarkusTransaction.requiringNew().call(() ->
                    overgangsfunctie.voerUit(id, NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR)));

            assertTrue(eerste.get(10, TimeUnit.SECONDS).isUitgevoerd());
            OvergangUitkomst tweedeUitkomst = tweede.get(10, TimeUnit.SECONDS);

            assertTrue(tweedeUitkomst.isUitgevoerd());
            assertEquals(NotificatieStatus.BEZORGD, tweedeUitkomst.van());
        } finally {
            pool.shutdownNow();
        }

        QuarkusTransaction.requiringNew().run(() -> {
            List<Long> volgnummers = eventRepository.findByNotificatie(id).stream().map(Event::getVolgnummer).toList();

            assertEquals(List.of(0L, 1L, 2L), volgnummers);
            assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatieRepository.findById(id).getStatus());
        });
    }

    // Neemt een notificatie aan en zet de status daarna rechtstreeks, zodat elke status als
    // vertrekpunt te testen is zonder de hele keten te doorlopen. De versie blijft 0. De trigger
    // staat daarvoor in die transactie uit (replica-rol).
    private UUID aangenomenNotificatieMetStatus(NotificatieStatus status) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            var entityManager = notificatieRepository.getEntityManager();
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            entityManager.createNativeQuery("UPDATE notificatie SET status = ?1 WHERE id = ?2")
                    .setParameter(1, status.name())
                    .setParameter(2, id)
                    .executeUpdate();
        });

        return id;
    }

    static Stream<Arguments> toegestaneOvergangen() {
        List<Arguments> paren = new ArrayList<>();
        Overgangsregels.alle().forEach((van, naar) -> naar.forEach(n -> paren.add(Arguments.of(van, n))));

        return paren.stream();
    }

    static Stream<Arguments> nietToegestaneOvergangen() {
        List<Arguments> paren = new ArrayList<>();

        for (NotificatieStatus van : NotificatieStatus.values()) {
            for (NotificatieStatus naar : NotificatieStatus.values()) {
                if (!Overgangsregels.isToegestaan(van, naar)) {
                    paren.add(Arguments.of(van, naar));
                }
            }
        }

        return paren.stream();
    }
}
