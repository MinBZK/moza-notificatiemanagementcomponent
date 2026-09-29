package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReceiptVerwerkerTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-01-15T10:00:00Z");
    private static final OffsetDateTime T2 = T1.plusMinutes(5);

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

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    // Een tijdelijke of technische fout op de eerste poging plant een herverzending; zie HerverzendingTest.
    @ParameterizedTest
    @CsvSource({
            "delivered, BEZORGD, BEZORGD, ",
            "permanent-failure, PERMANENT_MISLUKT, NIET_BEZORGBAAR, ONBEREIKBAAR",
            "DELIVERED, BEZORGD, BEZORGD, "
    })
    void verwerk_uitkomst_legtVastOpPogingEnVoertOvergangUit(String receipt, PogingStatus pogingStatus,
                                                             NotificatieStatus naar, Reden reden) {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, receipt, T1);

        Poging poging = poging(notifyId);
        assertEquals(pogingStatus, poging.getStatus());
        assertEquals(T1, poging.getReceiptTijdstip());
        assertEquals(naar, notificatie(notifyId).getStatus());
        assertEquals(reden, notificatie(notifyId).getReden());

        Event event = overgangenNaVerzending(notifyId, 1).getFirst();
        assertEquals(NotificatieStatus.VERZONDEN, event.getVan());
        assertEquals(naar, event.getNaar());
        assertEquals(reden, event.getReden());
        assertEquals(3, event.getVolgnummer());
    }

    @Test
    void verwerk_zelfdeReceiptTweeKeer_voertEenOvergangUit() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "delivered", T1);
        receiptVerwerker.verwerk(notifyId, null, "delivered", T1);

        assertEquals(3, notificatie(notifyId).getVersie());
        overgangenNaVerzending(notifyId, 1);
    }

    // De volgorde komt uit het tijdstip in de receipt, niet uit de aankomst.
    @Test
    void verwerk_oudereReceiptNaEenNieuwere_wordtGenegeerd() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "delivered", T2);
        receiptVerwerker.verwerk(notifyId, null, "permanent-failure", T1);

        assertEquals(PogingStatus.BEZORGD, poging(notifyId).getStatus());
        assertEquals(T2, poging(notifyId).getReceiptTijdstip());
        assertEquals(NotificatieStatus.BEZORGD, notificatie(notifyId).getStatus());
        overgangenNaVerzending(notifyId, 1);
    }

    // NotifyNL kan na delivered nog een fout melden; bezorgd is geen eindstatus.
    @Test
    void verwerk_faalreceiptNaDelivered_brengtDeNotificatieNaarNietBezorgbaar() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "delivered", T1);
        receiptVerwerker.verwerk(notifyId, null, "permanent-failure", T2);

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(notifyId).getStatus());
        List<Event> events = overgangenNaVerzending(notifyId, 2);
        assertEquals(List.of(3L, 4L), events.stream().map(Event::getVolgnummer).toList());
    }

    // Een eindstatus is absorberend: de latere receipt staat op de poging, de notificatie blijft staan.
    @Test
    void verwerk_receiptNaEenEindstatus_legtVastOpDePogingZonderOvergang() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "permanent-failure", T1);
        receiptVerwerker.verwerk(notifyId, null, "delivered", T2);

        assertEquals(PogingStatus.BEZORGD, poging(notifyId).getStatus());
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(notifyId).getStatus());
        assertEquals(3, notificatie(notifyId).getVersie());
        overgangenNaVerzending(notifyId, 1);
    }

    // Een status die de NMC niet kent verandert niets; hij hoort op ERROR op te vallen, met de ruwe
    // waarde en de referentie, zodat de lijst uitgebreid kan worden.
    @Test
    void verwerk_onbekendeStatus_wijzigtNietsEnLogtOpError() {
        UUID notifyId = verzondenNotificatie();

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(ReceiptVerwerker.class)) {
            receiptVerwerker.verwerk(notifyId, null, "een-rare-status", T1);
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(PogingStatus.VERZONDEN, poging(notifyId).getStatus());
        assertNull(poging(notifyId).getReceiptTijdstip());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(notifyId).getStatus());
        overgangenNaVerzending(notifyId, 0);
        assertEquals(1, fouten.size());
        assertTrue(fouten.getFirst().contains("een-rare-status"));
        assertTrue(fouten.getFirst().contains(notifyId.toString()));
    }

    @Test
    void verwerk_tussenstatus_wijzigtNiets() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "sending", T1);

        assertEquals(PogingStatus.VERZONDEN, poging(notifyId).getStatus());
        overgangenNaVerzending(notifyId, 0);
    }

    @Test
    void verwerk_onbekendNotifyId_gooitNietGevonden() {
        assertThrows(NotificatieNietGevondenException.class,
                () -> receiptVerwerker.verwerk(UUID.randomUUID(), null, "delivered", T1));
    }

    @Test
    void verwerk_zonderTijdstip_gebruiktDeEigenKlok() {
        UUID notifyId = verzondenNotificatie();
        OffsetDateTime ervoor = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        receiptVerwerker.verwerk(notifyId, null, "delivered", null);

        assertFalse(poging(notifyId).getReceiptTijdstip().isBefore(ervoor));
    }

    // Een tijdstip in de toekomst zou elke latere receipt blokkeren; het wordt begrensd op nu.
    @Test
    void verwerk_tijdstipInDeToekomst_wordtBegrensdOpNu() {
        UUID notifyId = verzondenNotificatie();

        receiptVerwerker.verwerk(notifyId, null, "delivered", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertFalse(poging(notifyId).getReceiptTijdstip().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private UUID verzondenNotificatie() {
        UUID notifyId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            overgangsfunctie.neemAan(notificatie);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            poging.markeerVerzonden(notifyId, T1.minusMinutes(1));
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
        });

        return notifyId;
    }

    private Poging poging(UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findByNotifyId(notifyId).orElseThrow());
    }

    private Notificatie notificatie(UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() ->
                notificatieRepository.findById(pogingRepository.findByNotifyId(notifyId).orElseThrow().getNotificatieId()));
    }

    // De events na de overgang naar verzonden (volgnummer 2): wat de receipts hebben vastgelegd.
    private List<Event> overgangenNaVerzending(UUID notifyId, int aantal) {
        List<Event> events = QuarkusTransaction.requiringNew().call(() -> eventRepository
                .findByNotificatie(pogingRepository.findByNotifyId(notifyId).orElseThrow().getNotificatieId())
                .stream().filter(e -> e.getVolgnummer() > 2).toList());
        assertEquals(aantal, events.size());

        return events;
    }
}
