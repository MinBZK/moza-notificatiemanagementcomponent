package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.StatusUpdateOpdracht;
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
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    @InjectMock
    ConsumentCallbackAdapter consumentCallbackAdapter;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "delivered, BEZORGD, BEZORGD, ",
            "permanent-failure, PERMANENT_MISLUKT, NIET_BEZORGBAAR, ONBEREIKBAAR",
            "temporary-failure, TIJDELIJK_MISLUKT, NIET_BEZORGBAAR, ONBEREIKBAAR",
            "technical-failure, TECHNISCH_MISLUKT, TECHNISCH_MISLUKT, TECHNISCH",
            "DELIVERED, BEZORGD, BEZORGD, "
    })
    void verwerk_uitkomst_legtVastOpPogingEnVoertOvergangUit(String receipt, PogingStatus pogingStatus,
                                                             NotificatieStatus naar, Reden reden) {
        UUID notifyId = verzondenNotificatie("https://omc.example.nl/callback");

        receiptVerwerker.verwerk(notifyId, receipt, T1);

        Poging poging = poging(notifyId);
        assertEquals(pogingStatus, poging.getStatus());
        assertEquals(T1, poging.getReceiptTijdstip());
        assertEquals(naar, notificatie(notifyId).getStatus());
        assertEquals(reden, notificatie(notifyId).getReden());

        StatusUpdateOpdracht opdracht = verstuurdeOpdrachten(1).getFirst();
        assertEquals(NotificatieStatus.VERZONDEN, opdracht.van());
        assertEquals(naar, opdracht.naar());
        assertEquals(reden, opdracht.reden());
        assertEquals(3, opdracht.versie());
        assertEquals("https://omc.example.nl/callback", opdracht.callbackUrl());
    }

    @Test
    void verwerk_zelfdeReceiptTweeKeer_voertEenOvergangUit() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "delivered", T1);
        receiptVerwerker.verwerk(notifyId, "delivered", T1);

        assertEquals(3, notificatie(notifyId).getVersie());
        verstuurdeOpdrachten(1);
    }

    // De volgorde komt uit het tijdstip in de receipt, niet uit de aankomst.
    @Test
    void verwerk_oudereReceiptNaEenNieuwere_wordtGenegeerd() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "delivered", T2);
        receiptVerwerker.verwerk(notifyId, "permanent-failure", T1);

        assertEquals(PogingStatus.BEZORGD, poging(notifyId).getStatus());
        assertEquals(T2, poging(notifyId).getReceiptTijdstip());
        assertEquals(NotificatieStatus.BEZORGD, notificatie(notifyId).getStatus());
        verstuurdeOpdrachten(1);
    }

    // NotifyNL kan na delivered nog een fout melden; bezorgd is geen eindstatus.
    @Test
    void verwerk_faalreceiptNaDelivered_brengtDeNotificatieNaarNietBezorgbaar() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "delivered", T1);
        receiptVerwerker.verwerk(notifyId, "permanent-failure", T2);

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(notifyId).getStatus());
        List<StatusUpdateOpdracht> opdrachten = verstuurdeOpdrachten(2);
        assertEquals(List.of(3L, 4L), opdrachten.stream().map(StatusUpdateOpdracht::versie).toList());
    }

    // Een eindstatus is absorberend: de latere receipt staat op de poging, de notificatie blijft staan.
    @Test
    void verwerk_receiptNaEenEindstatus_legtVastOpDePogingZonderOvergang() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "permanent-failure", T1);
        receiptVerwerker.verwerk(notifyId, "delivered", T2);

        assertEquals(PogingStatus.BEZORGD, poging(notifyId).getStatus());
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(notifyId).getStatus());
        assertEquals(3, notificatie(notifyId).getVersie());
        verstuurdeOpdrachten(1);
    }

    // Een status die de NMC niet kent verandert niets; hij hoort op ERROR op te vallen, met de ruwe
    // waarde en de referentie, zodat de lijst uitgebreid kan worden.
    @Test
    void verwerk_onbekendeStatus_wijzigtNietsEnLogtOpError() {
        UUID notifyId = verzondenNotificatie(null);

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(ReceiptVerwerker.class)) {
            receiptVerwerker.verwerk(notifyId, "een-rare-status", T1);
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(PogingStatus.VERZONDEN, poging(notifyId).getStatus());
        assertNull(poging(notifyId).getReceiptTijdstip());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(notifyId).getStatus());
        verify(consumentCallbackAdapter, never()).stuurStatusUpdate(any());
        assertEquals(1, fouten.size());
        assertTrue(fouten.getFirst().contains("een-rare-status"));
        assertTrue(fouten.getFirst().contains(notifyId.toString()));
    }

    @Test
    void verwerk_tussenstatus_wijzigtNiets() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "sending", T1);

        assertEquals(PogingStatus.VERZONDEN, poging(notifyId).getStatus());
        verify(consumentCallbackAdapter, never()).stuurStatusUpdate(any());
    }

    @Test
    void verwerk_onbekendNotifyId_gooitNietGevonden() {
        assertThrows(NotificatieNietGevondenException.class,
                () -> receiptVerwerker.verwerk(UUID.randomUUID(), "delivered", T1));
    }

    @Test
    void verwerk_zonderTijdstip_gebruiktDeEigenKlok() {
        UUID notifyId = verzondenNotificatie(null);
        OffsetDateTime ervoor = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        receiptVerwerker.verwerk(notifyId, "delivered", null);

        assertFalse(poging(notifyId).getReceiptTijdstip().isBefore(ervoor));
    }

    // Een tijdstip in de toekomst zou elke latere receipt blokkeren; het wordt begrensd op nu.
    @Test
    void verwerk_tijdstipInDeToekomst_wordtBegrensdOpNu() {
        UUID notifyId = verzondenNotificatie(null);

        receiptVerwerker.verwerk(notifyId, "delivered", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));

        assertFalse(poging(notifyId).getReceiptTijdstip().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private UUID verzondenNotificatie(String callbackUrl) {
        UUID notifyId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(callbackUrl);
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

    private List<StatusUpdateOpdracht> verstuurdeOpdrachten(int aantal) {
        ArgumentCaptor<StatusUpdateOpdracht> captor = ArgumentCaptor.forClass(StatusUpdateOpdracht.class);
        verify(consumentCallbackAdapter, times(aantal)).stuurStatusUpdate(captor.capture());

        return captor.getAllValues();
    }
}
