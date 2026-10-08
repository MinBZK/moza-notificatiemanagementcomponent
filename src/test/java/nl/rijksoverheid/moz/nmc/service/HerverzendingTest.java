package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetMultipleMessagesResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
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
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Eén herverzending per notificatie, en geen nieuwe poging na {@code geldig_tot}. */
@QuarkusTest
class HerverzendingTest {

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @InjectMock
    @RestClient
    GetMessageDataApi getMessageDataApi;

    @InjectMock
    NotifyNLJwtFactory notifyNLJwtFactory;

    @Inject
    AannameService aannameService;

    @Inject
    ReceiptVerwerker receiptVerwerker;

    @Inject
    TaakWorker taakWorker;

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
        when(sendAMessageApi.sendEmail(any())).thenAnswer(a -> new SendEmailResponse().id(UUID.randomUUID().toString()));
    }

    @ParameterizedTest
    @CsvSource({"temporary-failure", "technical-failure"})
    void faalOpDeEerstePoging_plantEenHerverzendingDieDeTweedePogingMaakt(String receipt) {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime voor = OffsetDateTime.now(ZoneOffset.UTC);

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), receipt, OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        Taak herverzending = enigeTaak();
        assertEquals(TaakSoort.VERZENDEN, herverzending.getSoort(), "de navraag van de eerste poging is afgerond");
        assertTrue(!herverzending.getDue().isBefore(voor.plusMinutes(59)), "herverzend-wachttijd van een uur");

        maakDue();
        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Poging tweede = laatstePoging(id);
        assertEquals(2, tweede.getNummer());
        assertEquals(PogingStatus.VERZONDEN, tweede.getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.VERZONDEN), overgangen(id), "de herverzending is een overgang met event");
        assertEquals(3, notificatie(id).getVersie());
        verify(sendAMessageApi, times(2)).sendEmail(any());
        Taak navraag = enigeTaak();
        assertEquals(TaakSoort.RECONCILIEREN, navraag.getSoort());
        assertEquals(tweede.getId().toString(), navraag.getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID));
    }

    // Terwijl de herverzending buiten een transactie loopt, meldt NotifyNL alsnog een bezorging van de
    // eerste poging; de weigering van de tweede daarna overschrijft die niet.
    @Test
    void bezorgingVanDeEerstePogingTijdensDeHerverzending_gaatVoorDeWeigering() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime fout = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", fout);
        when(sendAMessageApi.sendEmail(any())).thenAnswer(a -> {
            receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "delivered", fout.plusMinutes(1));

            throw new WebApplicationException(Response.status(400).entity("{\"errors\":[{\"error\":\"ValidationError\",\"message\":\"email_address Not a valid email address\"}]}").build());
        });
        maakDue();

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.BEZORGD, notificatie(id).getStatus());
    }

    // Een tweede faalreceipt voor de eerste poging, zolang de herverzending wacht: bijvoorbeeld voor een
    // duplicaat-id, of een technical-failure gevolgd door een temporary-failure.
    @ParameterizedTest
    @CsvSource({"temporary-failure, temporary-failure", "technical-failure, temporary-failure"})
    void tweedeFaalreceiptVoorDeEerstePoging_plantGeenTweedeHerverzending(String eerste, String tweede) {
        UUID id = verzonden();
        Poging poging = laatstePoging(id);
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        receiptVerwerker.verwerk(poging.getNotifyId(), poging.getId().toString(), eerste, nu.minusMinutes(2));

        receiptVerwerker.verwerk(poging.getNotifyId(), poging.getId().toString(), tweede, nu.minusMinutes(1));

        assertEquals(PogingStatus.TIJDELIJK_MISLUKT, poging(poging.getId()).getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(TaakSoort.VERZENDEN, enigeTaak().getSoort());
    }

    @ParameterizedTest
    @CsvSource({
            "temporary-failure, NIET_BEZORGBAAR, ONBEREIKBAAR",
            "technical-failure, TECHNISCH_MISLUKT, TECHNISCH"
    })
    void faalOpDeTweedePoging_isTerminaal(String receipt, NotificatieStatus naar, Reden reden) {
        UUID id = naHerverzending();
        Poging tweede = laatstePoging(id);

        receiptVerwerker.verwerk(tweede.getNotifyId(), tweede.getId().toString(), receipt, OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(naar, notificatie(id).getStatus());
        assertEquals(reden, notificatie(id).getReden());
        assertTrue(taken().isEmpty());
    }

    @Test
    void lateFaalreceiptVoorDeEerstePoging_wordtAlleenOpDiePogingVastgelegd() {
        UUID id = naHerverzending();
        Poging eerste = pogingen(id).getFirst();

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "permanent-failure",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(PogingStatus.PERMANENT_MISLUKT, poging(eerste.getId()).getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
    }

    @Test
    void faalNaGeldigTot_geeftVerlopenInPlaatsVanEenHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        verzetGeldigTot(id);

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(Reden.VERLOPEN, notificatie(id).getReden());
        assertTrue(taken().isEmpty());
    }

    @Test
    void herverzendtaakNaGeldigTot_startGeenPogingMeer() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                OffsetDateTime.now(ZoneOffset.UTC));
        verzetGeldigTot(id);
        maakDue();

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(1, pogingen(id).size());
        verify(sendAMessageApi, times(1)).sendEmail(any());
        assertTrue(taken().isEmpty());
    }

    @Test
    void eersteVerzendingNaGeldigTot_verloopt() {
        UUID id = aannameService.neemAan(opdracht());
        verzetGeldigTot(id);

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.VERLOPEN), overgangen(id));
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    // Een storing bij NotifyNL stelt de taak uit; loopt dat uitstel voorbij geldig_tot en ligt de poging
    // niet bij NotifyNL, dan verloopt de notificatie.
    @Test
    void uitstelVoorbijGeldigTot_verlooptAlsDePogingNietBijNotifyNLLigt() {
        when(sendAMessageApi.sendEmail(any())).thenThrow(new WebApplicationException(Response.status(503).build()));
        when(getMessageDataApi.getMultipleMessagesStatus(any(), any(), any(), any(), any()))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of()));
        UUID id = aannameService.neemAan(opdracht());
        taakWorker.verwerk(TaakSoort.VERZENDEN);
        assertEquals(NotificatieStatus.IN_VERZENDING, notificatie(id).getStatus());
        verzetGeldigTot(id);
        maakDue();

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        verify(sendAMessageApi, times(1)).sendEmail(any());
    }

    @Test
    void tijdelijkeFoutBinnenDeVaststellingstermijn_brengtBezorgdTerugNaarVerzondenMetHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime bezorgdOp = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "delivered", bezorgdOp);
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, enigeTaak().getSoort());

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", bezorgdOp.plusDays(1));

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(TaakSoort.VERZENDEN, enigeTaak().getSoort(), "de vaststeltaak is vervangen door de herverzending");
    }

    @Test
    void tijdelijkeFoutOpBezorgstatusOnbekend_wordtAlleenOpDePogingVastgelegd() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        QuarkusTransaction.requiringNew().run(() -> {
            pogingRepository.findById(eerste.getId()).markeerOnbekend();
            overgangsfunctie.voerUit(id, NotificatieStatus.BEZORGSTATUS_ONBEKEND, null);
        });

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                OffsetDateTime.now(ZoneOffset.UTC));

        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie(id).getStatus());
        assertEquals(PogingStatus.TIJDELIJK_MISLUKT, poging(eerste.getId()).getStatus());
        assertTrue(taken().stream().noneMatch(t -> t.getSoort() == TaakSoort.VERZENDEN));
    }

    @Test
    void tijdelijkeFoutNaEenPermanenteFout_plantGeenHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "permanent-failure", nu.minusMinutes(1));

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", nu);

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(id).getStatus());
        assertTrue(taken().isEmpty());
    }

    @Test
    void controletaak_plantDeOntbrekendeHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                OffsetDateTime.now(ZoneOffset.UTC));
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            taakRepository.persist(new Taak(TaakSoort.CONTROLE, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null));
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertTrue(taken().stream().anyMatch(t -> t.getSoort() == TaakSoort.VERZENDEN && id.equals(t.getNotificatieId())));
    }

    @Test
    void aanname_legtBerichttypeEnGeldigTotVast() {
        OffsetDateTime voor = OffsetDateTime.now(ZoneOffset.UTC);

        UUID id = aannameService.neemAan(opdracht());

        Notificatie notificatie = notificatie(id);
        assertEquals(BerichtType.DEMO_TEMPLATE.name(), notificatie.getBerichtType());
        assertTrue(!notificatie.getGeldigTot().isBefore(voor.plusDays(7)), "standaardgeldigheid van zeven dagen");
    }

    // Een notificatie op verzonden, met een lopende eerste poging en haar navraagtaak.
    private UUID verzonden() {
        UUID id = aannameService.neemAan(opdracht());
        taakWorker.verwerk(TaakSoort.VERZENDEN);
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());

        return id;
    }

    // Een notificatie met een tweede, lopende poging na een tijdelijke fout op de eerste.
    private UUID naHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        maakDue();
        taakWorker.verwerk(TaakSoort.VERZENDEN);
        assertEquals(2, laatstePoging(id).getNummer());

        return id;
    }

    private static AannameOpdracht opdracht() {
        return new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email("burger@example.nl"), null, null,
                BerichtType.DEMO_TEMPLATE, Map.of());
    }

    private void verzetGeldigTot(UUID id) {
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verzetGeldigTot(taakRepository.getEntityManager(), id,
                OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(1))));
    }

    private void maakDue() {
        QuarkusTransaction.requiringNew().run(() -> taakRepository.update("due = ?1 where soort = ?2",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), TaakSoort.VERZENDEN));
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    private Poging poging(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findById(id));
    }

    private Poging laatstePoging(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findLaatsteVan(notificatieId).orElseThrow());
    }

    private List<Poging> pogingen(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.list("notificatieId = ?1 order by nummer", notificatieId));
    }

    private List<Taak> taken() {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.listAll());
    }

    private Taak enigeTaak() {
        List<Taak> taken = taken();
        assertEquals(1, taken.size(), taken.stream().map(Taak::getSoort).toList().toString());

        return taken.getFirst();
    }

    private List<NotificatieStatus> overgangen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList());
    }
}
