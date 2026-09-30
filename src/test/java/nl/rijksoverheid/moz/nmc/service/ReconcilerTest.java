package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetOneMessageResponse;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// %test: 20 tokens per minuut, waarvan 5 voor de navraag.
@QuarkusTest
class ReconcilerTest {

    @InjectMock
    @RestClient
    GetMessageDataApi getMessageDataApi;

    @InjectMock
    NotifyNLJwtFactory notifyNLJwtFactory;

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
            taakRepository.getEntityManager().createNativeQuery("DELETE FROM verzendbudget").executeUpdate();
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
        when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }

    @Test
    void navraag_delivered_brengtDeNotificatieNaarBezorgdEnPlantDeVaststeltaak() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        OffsetDateTime afgeleverd = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.MICROS);
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenReturn(new GetOneMessageResponse().status("delivered").completedAt(afgeleverd.toString()));

        assertEquals(1, taakWorker.verwerk(TaakSoort.RECONCILIEREN));

        assertEquals(NotificatieStatus.BEZORGD, notificatie(verzonden.notificatieId()).getStatus());
        assertEquals(PogingStatus.BEZORGD, poging(verzonden.pogingId()).getStatus());
        List<Taak> taken = taken();
        assertEquals(1, taken.size(), "de navraag is af, de vaststeltaak staat");
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, taken.getFirst().getSoort());
        assertEquals(afgeleverd.plusDays(8).toInstant(), taken.getFirst().getDue().toInstant(),
                "vaststellingstermijn plus callback-venster na het tijdstip uit NotifyNL");
    }

    @Test
    void navraag_404_leidtTotBezorgstatusOnbekend_enEenLateReceiptCorrigeertDie() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenThrow(new WebApplicationException(Response.status(404).build()));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie(verzonden.notificatieId()).getStatus());
        assertEquals(PogingStatus.ONBEKEND, poging(verzonden.pogingId()).getStatus());
        assertTrue(taken().isEmpty());

        receiptVerwerker.verwerk(verzonden.notifyId(), verzonden.pogingId().toString(), "delivered",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(NotificatieStatus.BEZORGD, notificatie(verzonden.notificatieId()).getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGSTATUS_ONBEKEND, NotificatieStatus.BEZORGD), overgangen(verzonden.notificatieId()));
    }

    @Test
    void navraag_tussenstatus_plantDeVolgendeNavraag() {
        OffsetDateTime verzondenOp = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).truncatedTo(ChronoUnit.MICROS);
        Verzonden verzonden = verzonden(verzondenOp);
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenReturn(new GetOneMessageResponse().status("sending"));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        Taak navraag = taken().getFirst();
        assertEquals(TaakSoort.RECONCILIEREN, navraag.getSoort());
        assertEquals(verzondenOp.plusHours(6).toInstant(), navraag.getDue().toInstant());
        assertEquals(0, navraag.getPogingen());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(verzonden.notificatieId()).getStatus());
    }

    // Met de callback bij NotifyNL uitgeschakeld eindigt de notificatie toch in een eindstatus.
    @Test
    void navraag_tussenstatusNaDeBewaartermijn_leidtTotBezorgstatusOnbekend() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusDays(8));
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenReturn(new GetOneMessageResponse().status("pending"));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie(verzonden.notificatieId()).getStatus());
        assertTrue(taken().isEmpty());
    }

    @Test
    void navraag_storingBijNotifyNL_steltUitZonderPoging() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenThrow(new WebApplicationException(Response.status(503).build()));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        Taak navraag = taken().getFirst();
        assertEquals(0, navraag.getPogingen());
        assertTrue(navraag.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(4)));
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(verzonden.notificatieId()).getStatus());
    }

    // Een navraag die blijft mislukken (hier een ingetrokken key) houdt ook op bij de bewaartermijn.
    @Test
    void navraag_storingNaDeBewaartermijn_leidtTotBezorgstatusOnbekend() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusDays(8));
        when(getMessageDataApi.getMessageData(verzonden.notifyId().toString()))
                .thenThrow(new WebApplicationException(Response.status(403).build()));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie(verzonden.notificatieId()).getStatus());
        assertTrue(taken().isEmpty());
    }

    @Test
    void navraag_pogingMetUitkomst_rondtAfZonderOpvraag() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.findById(verzonden.pogingId())
                .verwerkReceipt(PogingStatus.BEZORGD, OffsetDateTime.now(ZoneOffset.UTC)));

        taakWorker.verwerk(TaakSoort.RECONCILIEREN);

        verify(getMessageDataApi, never()).getMessageData(any());
        assertTrue(taken().isEmpty());
    }

    @Test
    void receipt_rondtDeOpenNavraagAf() {
        Verzonden verzonden = verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));

        receiptVerwerker.verwerk(verzonden.notifyId(), verzonden.pogingId().toString(), "permanent-failure",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertTrue(taken().isEmpty());
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(verzonden.notificatieId()).getStatus());
    }

    @Test
    void navraag_claimtNietMeerDanHetAandeelVanDeNavraag() {
        for (int i = 0; i < 8; i++) {
            verzonden(OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
        }

        when(getMessageDataApi.getMessageData(any())).thenReturn(new GetOneMessageResponse().status("sending"));

        assertEquals(5, taakWorker.verwerk(TaakSoort.RECONCILIEREN));
        assertEquals(0, taakWorker.verwerk(TaakSoort.RECONCILIEREN), "het aandeel voor dit tijdvak is op");
    }

    private record Verzonden(UUID notificatieId, UUID pogingId, UUID notifyId) {
    }

    // Een verzonden notificatie met een lopende poging en een navraagtaak die aan de beurt is.
    private Verzonden verzonden(OffsetDateTime verzondenOp) {
        UUID notifyId = UUID.randomUUID();

        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            overgangsfunctie.neemAan(notificatie);
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.IN_VERZENDING, null);
            Poging poging = new Poging(notificatie.getId(), 1);
            pogingRepository.persist(poging);
            poging.markeerVerzonden(notifyId, verzondenOp.truncatedTo(ChronoUnit.MICROS));
            overgangsfunctie.voerUit(notificatie.getId(), NotificatieStatus.VERZONDEN, null);
            taakRepository.persist(new Taak(TaakSoort.RECONCILIEREN, NotificatieFixtures.DV_ID, notificatie.getId(),
                    OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofSeconds(1)), null,
                    Map.of(VerzendTaakHandler.PAYLOAD_POGING_ID, poging.getId().toString())));

            return new Verzonden(notificatie.getId(), poging.getId(), notifyId);
        });
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

    private List<NotificatieStatus> overgangen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList());
    }
}
