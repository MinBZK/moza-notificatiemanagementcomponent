package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.OvergangUitkomst;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: wistermijn 7 dagen.
@QuarkusTest
class WisTaakHandlerTest {

    private static final Ontvanger ONTVANGER = Ontvanger.email("burger@example.nl");

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
        });
    }

    @Test
    void overgangNaarEindstatus_plantDeWistaakOpDeWistermijn() {
        UUID id = metEindstatus(NotificatieStatus.GEANNULEERD);

        Taak wistaak = wistaak(id);
        assertEquals(notificatie(id).getEindstatusOp().plusDays(7).toInstant(), wistaak.getDue().toInstant());
    }

    @Test
    void overgangNaarGeenEindstatus_plantGeenWistaak() {
        UUID id = aangenomen();
        overgang(id, NotificatieStatus.IN_VERZENDING);

        assertNull(notificatie(id).getEindstatusOp());
        assertTrue(taken(TaakSoort.WISSEN).isEmpty());
    }

    // Een late receipt corrigeert bezorgstatus-onbekend naar bezorgd; zonder open wistaak kan de
    // controletaak de notificatie weer oppakken, en de volgende eindstatus plant een nieuwe.
    @Test
    void correctieVanBezorgstatusOnbekendNaarBezorgd_verwijdertDeWistaak() {
        UUID id = bezorgstatusOnbekend();

        overgang(id, NotificatieStatus.BEZORGD);

        assertNull(notificatie(id).getEindstatusOp());
        assertTrue(taken(TaakSoort.WISSEN).isEmpty());
    }

    @Test
    void correctieVanBezorgstatusOnbekendNaarNietBezorgbaar_verzetDeWistaakNaarDeNieuweTermijn() {
        UUID id = bezorgstatusOnbekend();
        OffsetDateTime eerder = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.verzetEindstatusOp(entityManager, id, eerder);
            TaakFixtures.zetDue(entityManager, TaakSoort.WISSEN, eerder.plusDays(7));
        });

        overgang(id, NotificatieStatus.NIET_BEZORGBAAR);

        OffsetDateTime eindstatusOp = notificatie(id).getEindstatusOp();
        assertTrue(eindstatusOp.isAfter(eerder.plusDays(2)));
        assertEquals(List.of(eindstatusOp.plusDays(7).toInstant()),
                taken(TaakSoort.WISSEN).stream().map(t -> t.getDue().toInstant()).toList(), "één wistaak, verzet");
    }

    @Test
    void wistaak_naDeWistermijn_wistDeSleutelZonderVersieOfEvent() {
        UUID id = metEindstatus(NotificatieStatus.GEANNULEERD);
        Notificatie voor = notificatie(id);
        int eventsVoor = eventRepository.findByNotificatie(id).size();
        verzetNaarVerleden(id, 8);

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.WISSEN));

        Notificatie na = notificatie(id);
        VersleuteldeGegevens gegevens = na.getVersleuteldeGegevens();
        assertNull(gegevens.sleutelGewrapt());
        assertNull(gegevens.kekVersie());
        assertArrayEquals(voor.getVersleuteldeGegevens().ontvangerVersleuteld(), gegevens.ontvangerVersleuteld(),
                "alleen de sleutel gaat weg; de rij is daarna pseudoniem");
        assertEquals(voor.getVersie(), na.getVersie());
        assertEquals(eventsVoor, eventRepository.findByNotificatie(id).size());
        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelOntvanger(id, gegevens));
        assertThrows(SleutelGewistException.class, () -> sleutelbeheer.ontsleutelPersonalisation(id, gegevens));
        assertTrue(taken(TaakSoort.WISSEN).isEmpty());
    }

    @Test
    void wistaak_voorDeWistermijn_steltUitTotDeTermijnZonderTeWissen() {
        UUID id = metEindstatus(NotificatieStatus.GEANNULEERD);
        zetDueInVerleden();

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.WISSEN));

        Taak wistaak = wistaak(id);
        assertEquals(notificatie(id).getEindstatusOp().plusDays(7).toInstant(), wistaak.getDue().toInstant());
        assertEquals(0, wistaak.getPogingen());
        assertNull(wistaak.getLeaseTot());
        assertEquals(ONTVANGER, sleutelbeheer.ontsleutelOntvanger(id, notificatie(id).getVersleuteldeGegevens()));
    }

    @Test
    void wistaak_notificatieZonderEindstatus_rondtAfZonderTeWissen() {
        UUID id = aangenomen();
        QuarkusTransaction.requiringNew().run(() -> taakRepository.persist(new Taak(TaakSoort.WISSEN,
                NotificatieFixtures.DV_ID, id, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, null)));

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.WISSEN));

        assertTrue(taken(TaakSoort.WISSEN).isEmpty());
        assertNotNull(notificatie(id).getVersleuteldeGegevens().sleutelGewrapt());
    }

    // De sleutelkolommen zijn niet updatable: een overgang op een entity die vóór het wissen is geladen,
    // schrijft de gewiste sleutel niet terug.
    @Test
    void overgang_opEenVoorHetWissenGeladenEntity_schrijftDeSleutelNietTerug() {
        UUID id = bezorgstatusOnbekend();

        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(notificatieRepository.findById(id).getVersleuteldeGegevens().sleutelGewrapt());
            QuarkusTransaction.requiringNew().run(() -> notificatieRepository.wisSleutel(id));
            overgangsfunctie.voerUit(id, NotificatieStatus.NIET_BEZORGBAAR, Reden.ONBEREIKBAAR);
        });

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(id).getStatus());
        assertNull(notificatie(id).getVersleuteldeGegevens().sleutelGewrapt());
    }

    private UUID aangenomen() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            notificatie.bewaarVersleuteldeGegevens(sleutelbeheer.versleutel(notificatie.getId(), ONTVANGER,
                    Map.of("naam", "Voorbeeld BV")));
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });
    }

    private UUID metEindstatus(NotificatieStatus status) {
        UUID id = aangenomen();
        overgang(id, status);

        return id;
    }

    private UUID bezorgstatusOnbekend() {
        UUID id = aangenomen();
        overgang(id, NotificatieStatus.IN_VERZENDING);
        overgang(id, NotificatieStatus.VERZONDEN);
        overgang(id, NotificatieStatus.BEZORGSTATUS_ONBEKEND);

        return id;
    }

    private void overgang(UUID id, NotificatieStatus naar) {
        QuarkusTransaction.requiringNew().run(() -> {
            OvergangUitkomst uitkomst = overgangsfunctie.voerUit(id, naar, naar.isEindstatus() ? Reden.TECHNISCH : null);
            assertTrue(uitkomst.isUitgevoerd(), "overgang naar " + naar);
        });
    }

    private void verzetNaarVerleden(UUID id, int dagen) {
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verzetEindstatusOp(entityManager, id,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(dagen)));
        zetDueInVerleden();
    }

    private void zetDueInVerleden() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetDue(entityManager, TaakSoort.WISSEN,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    private Taak wistaak(UUID id) {
        List<Taak> wistaken = taken(TaakSoort.WISSEN);
        assertEquals(1, wistaken.size());
        assertEquals(id, wistaken.getFirst().getNotificatieId());

        return wistaken.getFirst();
    }

    private List<Taak> taken(TaakSoort taakSoort) {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.list(Taak_.TAAK_SOORT, taakSoort));
    }
}
