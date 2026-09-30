package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: vaststellingstermijn 7 dagen, callback-venster 1 dag.
@QuarkusTest
class BezorgingVaststellenTest {

    @Inject
    TaakWorker taakWorker;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    TaakRepository taakRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void vaststeltaak_naDeTermijn_maaktDeBezorgingDefinitief() {
        Bezorgd bezorgd = bezorgd(OffsetDateTime.now(ZoneOffset.UTC).minusDays(9));
        maakVaststeltaakDue(bezorgd.notificatieId());

        assertEquals(1, taakWorker.verwerk(TaakSoort.BEZORGING_VASTSTELLEN));

        assertEquals(NotificatieStatus.DEFINITIEF_BEZORGD, notificatie(bezorgd.notificatieId()).getStatus());
        assertTrue(taken().isEmpty());
    }

    @Test
    void faalreceiptBinnenDeTermijn_volgtDeGewoneRegelsEnRondtDeVaststeltaakAf() {
        OffsetDateTime bezorgdOp = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        Bezorgd bezorgd = bezorgd(bezorgdOp);

        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "permanent-failure", bezorgdOp.plusDays(2));

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(bezorgd.notificatieId()).getStatus());
        assertTrue(taken().isEmpty(), "de open vaststeltaak is afgerond");
    }

    // De receipt komt binnen het callback-venster, dus vóór de vaststeltaak, maar zijn tijdstip ligt na de termijn.
    @Test
    void faalreceiptNaDeTermijn_wordtAlleenOpDePogingVastgelegd() {
        OffsetDateTime bezorgdOp = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7).minusHours(6);
        Bezorgd bezorgd = bezorgd(bezorgdOp);

        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "permanent-failure",
                bezorgdOp.plusDays(7).plusHours(1));

        assertEquals(NotificatieStatus.BEZORGD, notificatie(bezorgd.notificatieId()).getStatus());
        assertEquals(PogingStatus.PERMANENT_MISLUKT, poging(bezorgd.pogingId()).getStatus());
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, taken().getFirst().getSoort(), "de vaststeltaak blijft staan");

        maakVaststeltaakDue(bezorgd.notificatieId());
        taakWorker.verwerk(TaakSoort.BEZORGING_VASTSTELLEN);

        assertEquals(NotificatieStatus.DEFINITIEF_BEZORGD, notificatie(bezorgd.notificatieId()).getStatus());
    }

    // Ook als de poging na de eerste late faalreceipt niet meer op bezorgd staat, blijft de termijn gelden.
    @Test
    void tweedeFaalreceiptNaDeTermijn_wordtOokAlleenOpDePogingVastgelegd() {
        OffsetDateTime bezorgdOp = OffsetDateTime.now(ZoneOffset.UTC).minusDays(7).minusHours(6);
        Bezorgd bezorgd = bezorgd(bezorgdOp);

        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "temporary-failure",
                bezorgdOp.plusDays(7).plusHours(1));
        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "permanent-failure",
                bezorgdOp.plusDays(7).plusHours(2));

        assertEquals(NotificatieStatus.BEZORGD, notificatie(bezorgd.notificatieId()).getStatus());
        assertEquals(PogingStatus.PERMANENT_MISLUKT, poging(bezorgd.pogingId()).getStatus());
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, taken().getFirst().getSoort(), "de vaststeltaak blijft staan");
    }

    // Een technical-failure geeft vanuit bezorgd geen overgang, maar de termijn loopt nog vanaf de bezorging.
    @Test
    void faalreceiptBinnenDeTermijnNaEenGeweigerdeOvergang_volgtDeGewoneRegels() {
        OffsetDateTime bezorgdOp = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        Bezorgd bezorgd = bezorgd(bezorgdOp);

        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "technical-failure", bezorgdOp.plusDays(1));
        assertEquals(NotificatieStatus.BEZORGD, notificatie(bezorgd.notificatieId()).getStatus());

        receiptVerwerker.verwerk(bezorgd.notifyId(), bezorgd.pogingId().toString(), "permanent-failure", bezorgdOp.plusDays(2));

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(bezorgd.notificatieId()).getStatus());
        assertEquals(bezorgdOp.truncatedTo(ChronoUnit.MICROS).toInstant(), poging(bezorgd.pogingId()).getBezorgdOp().toInstant());
        assertTrue(taken().isEmpty());
    }

    @Test
    void vaststeltaak_notificatieNietMeerBezorgd_rondtAfZonderOvergang() {
        Bezorgd bezorgd = bezorgd(OffsetDateTime.now(ZoneOffset.UTC).minusDays(9));
        QuarkusTransaction.requiringNew().run(() ->
                overgangsfunctie.voerUit(bezorgd.notificatieId(), NotificatieStatus.VERLOPEN, Reden.VERLOPEN));
        maakVaststeltaakDue(bezorgd.notificatieId());

        taakWorker.verwerk(TaakSoort.BEZORGING_VASTSTELLEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(bezorgd.notificatieId()).getStatus());
        assertTrue(taken().isEmpty());
    }

    @Test
    void vaststeltaak_notificatieVerwijderd_rondtAf() {
        Bezorgd bezorgd = bezorgd(OffsetDateTime.now(ZoneOffset.UTC).minusDays(9));
        maakVaststeltaakDue(bezorgd.notificatieId());
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });

        taakWorker.verwerk(TaakSoort.BEZORGING_VASTSTELLEN);

        assertTrue(taken().isEmpty());
    }

    private record Bezorgd(UUID notificatieId, UUID pogingId, UUID notifyId) {
    }

    // Een notificatie die via een receipt op bezorgd kwam, met de vaststeltaak die dat plant.
    private Bezorgd bezorgd(OffsetDateTime bezorgdOp) {
        UUID notifyId = UUID.randomUUID();
        Bezorgd bezorgd = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            overgangsfunctie.neemAan(notificatie);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);
            poging.markeerVerzonden(notifyId, bezorgdOp.minusMinutes(1).truncatedTo(ChronoUnit.MICROS));
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);

            return new Bezorgd(notificatie.getId(), poging.getId(), notifyId);
        });
        receiptVerwerker.verwerk(notifyId, bezorgd.pogingId().toString(), "delivered", bezorgdOp);

        return bezorgd;
    }

    private void maakVaststeltaakDue(UUID notificatieId) {
        QuarkusTransaction.requiringNew().run(() -> taakRepository.update("due = ?1 where notificatieId = ?2",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), notificatieId));
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    private Poging poging(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findById(id));
    }

    private List<Taak> taken() {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.listAll());
    }
}
