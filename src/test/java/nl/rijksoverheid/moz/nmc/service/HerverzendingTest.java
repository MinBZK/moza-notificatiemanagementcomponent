package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetMultipleMessagesResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequest;
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
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.VerstelbareClock;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Herverzending volgens het verzendbeleid (standaard één), en geen nieuwe poging na {@code geldig_tot}. */
@QuarkusTest
class HerverzendingTest {

    // De klok staat stil, zodat de test exacte tijdstippen kan verwachten.
    private final VerstelbareClock clock = VerstelbareClock.opNu();

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

    @Inject
    VerzendTaakHandler verzendTaakHandler;

    @InjectSpy
    Verzendbeleid verzendbeleid;

    @BeforeEach
    void setUp() {
        QuarkusMock.installMockForType(clock, Clock.class);
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            TaakFixtures.leegVerzendbudget(taakRepository.getEntityManager());
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
        when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
        // Elke poging krijgt een eigen NotifyNL-id, afgeleid van haar reference: notify_id is uniek.
        when(sendAMessageApi.sendEmail(any())).thenAnswer(a -> new SendEmailResponse().id(UUID.nameUUIDFromBytes(
                a.<SendEmailRequest>getArgument(0).getReference().getBytes(StandardCharsets.UTF_8)).toString()));
    }

    @ParameterizedTest
    @CsvSource({"temporary-failure", "technical-failure"})
    void faalOpDeEerstePoging_plantEenHerverzendingDieDeTweedePogingMaakt(String receipt) {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime voor = clock.nu();

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), receipt, clock.nu());

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        Taak herverzending = enigeTaak();
        assertEquals(TaakSoort.VERZENDEN, herverzending.getTaakSoort(), "de navraag van de eerste poging is afgerond");
        assertEquals(voor.plusHours(1).toInstant(), herverzending.getDue().toInstant(), "herverzend-wachttijd van een uur");

        maakDue();
        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        Poging tweede = laatstePoging(id);
        assertEquals(2, tweede.getNummer());
        assertEquals(PogingStatus.VERZONDEN, tweede.getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.VERZONDEN), overgangen(id), "de herverzending is een overgang met event");
        assertEquals(3, notificatie(id).getVersie());
        verify(sendAMessageApi, times(2)).sendEmail(any());
        Taak navraag = enigeTaak();
        assertEquals(TaakSoort.RECONCILIEREN, navraag.getTaakSoort());
        assertEquals(tweede.getId().toString(), navraag.getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID));
    }

    // Terwijl de herverzending buiten een transactie loopt, meldt NotifyNL alsnog een bezorging van de
    // eerste poging; de weigering van de tweede daarna overschrijft die niet.
    @Test
    void bezorgingVanDeEerstePogingTijdensDeHerverzending_gaatVoorDeWeigering() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime fout = clock.nu().minusMinutes(2);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", fout);
        doAnswer(a -> {
            receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "delivered", fout.plusMinutes(1));

            throw new WebApplicationException(Response.status(400).entity("{\"errors\":[{\"error\":\"ValidationError\",\"message\":\"email_address Not a valid email address\"}]}").build());
        }).when(sendAMessageApi).sendEmail(any());
        maakDue();

        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.BEZORGD, notificatie(id).getStatus());
    }

    // Een tweede faalreceipt voor de eerste poging, zolang de herverzending wacht: bijvoorbeeld voor een
    // duplicaat-id, of een technical-failure gevolgd door een temporary-failure.
    @ParameterizedTest
    @CsvSource({"temporary-failure, temporary-failure", "technical-failure, temporary-failure"})
    void tweedeFaalreceiptVoorDeEerstePoging_plantGeenTweedeHerverzending(String eerste, String tweede) {
        UUID id = verzonden();
        Poging poging = laatstePoging(id);
        OffsetDateTime nu = clock.nu();
        receiptVerwerker.verwerk(poging.getNotifyId(), poging.getId().toString(), eerste, nu.minusMinutes(2));

        receiptVerwerker.verwerk(poging.getNotifyId(), poging.getId().toString(), tweede, nu.minusMinutes(1));

        assertEquals(PogingStatus.TIJDELIJK_MISLUKT, poging(poging.getId()).getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(TaakSoort.VERZENDEN, enigeTaak().getTaakSoort());
    }

    @ParameterizedTest
    @CsvSource({
            "temporary-failure, NIET_BEZORGBAAR, ONBEREIKBAAR",
            "technical-failure, TECHNISCH_MISLUKT, TECHNISCH"
    })
    void faalOpDeTweedePoging_leidtTotEenEindstatus(String receipt, NotificatieStatus naar, Reden reden) {
        UUID id = naHerverzending();
        Poging tweede = laatstePoging(id);

        receiptVerwerker.verwerk(tweede.getNotifyId(), tweede.getId().toString(), receipt, clock.nu());

        assertEquals(naar, notificatie(id).getStatus());
        assertEquals(reden, notificatie(id).getReden());
        assertEquals(List.of(TaakSoort.WISSEN), taken().stream().map(Taak::getTaakSoort).toList(), "alleen de wistaak van de eindstatus");
    }

    // Met twee herverzendingen geeft een fout op de tweede poging een derde; pas een fout op de derde
    // leidt tot een eindstatus.
    @Test
    void meerHerverzendingenInHetBeleid_gevenMeerPogingen() {
        doReturn(2).when(verzendbeleid).maxHerverzendingen(BerichtType.STUURGROEP_AGENDA.name());
        UUID id = aannameService.neemAan(opdracht(BerichtType.STUURGROEP_AGENDA));
        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        for (int nummer = 1; nummer <= 2; nummer++) {
            Poging poging = laatstePoging(id);
            assertEquals(nummer, poging.getNummer());
            receiptVerwerker.verwerk(poging.getNotifyId(), poging.getId().toString(), "temporary-failure",
                    clock.nu());
            assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
            maakDue();
            TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);
        }

        Poging derde = laatstePoging(id);
        assertEquals(3, derde.getNummer());
        receiptVerwerker.verwerk(derde.getNotifyId(), derde.getId().toString(), "temporary-failure",
                clock.nu());

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(id).getStatus());
        verify(sendAMessageApi, times(3)).sendEmail(any());
        assertEquals(List.of(TaakSoort.WISSEN), taken().stream().map(Taak::getTaakSoort).toList(), "alleen de wistaak van de eindstatus");
    }

    @Test
    void lateFaalreceiptVoorDeEerstePoging_wordtAlleenOpDiePogingVastgelegd() {
        UUID id = naHerverzending();
        Poging eerste = pogingen(id).getFirst();

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "permanent-failure",
                clock.nu());

        assertEquals(PogingStatus.PERMANENT_MISLUKT, poging(eerste.getId()).getStatus());
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
    }

    @Test
    void faalNaGeldigTot_geeftVerlopenInPlaatsVanEenHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        verzetGeldigTot(id);

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                clock.nu());

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(Reden.VERLOPEN, notificatie(id).getReden());
        assertEquals(List.of(TaakSoort.WISSEN), taken().stream().map(Taak::getTaakSoort).toList(), "alleen de wistaak van de eindstatus");
    }

    @Test
    void herverzendtaakNaGeldigTot_startGeenPogingMeer() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                clock.nu());
        verzetGeldigTot(id);
        maakDue();

        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(1, pogingen(id).size());
        verify(sendAMessageApi, times(1)).sendEmail(any());
        assertEquals(List.of(TaakSoort.WISSEN), taken().stream().map(Taak::getTaakSoort).toList(), "alleen de wistaak van de eindstatus");
    }

    @Test
    void eersteVerzendingNaGeldigTot_verloopt() {
        UUID id = aannameService.neemAan(opdracht());
        verzetGeldigTot(id);

        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.VERLOPEN), overgangen(id));
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    // Een storing bij NotifyNL stelt de taak uit; loopt dat uitstel voorbij geldig_tot en ligt de poging
    // niet bij NotifyNL, dan verloopt de notificatie.
    @Test
    void uitstelVoorbijGeldigTot_verlooptAlsDePogingNietBijNotifyNLLigt() {
        doThrow(new WebApplicationException(Response.status(503).build())).when(sendAMessageApi).sendEmail(any());
        when(getMessageDataApi.getMultipleMessagesStatus(any(), any(), any(), any(), any()))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of()));
        UUID id = aannameService.neemAan(opdracht());
        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);
        assertEquals(NotificatieStatus.IN_VERZENDING, notificatie(id).getStatus());
        verzetGeldigTot(id);
        maakDue();

        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERLOPEN, notificatie(id).getStatus());
        verify(sendAMessageApi, times(1)).sendEmail(any());
    }

    @Test
    void tijdelijkeFoutBinnenDeVaststellingstermijn_brengtBezorgdTerugNaarVerzondenMetHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime bezorgdOp = clock.nu().minusDays(2);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "delivered", bezorgdOp);
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, enigeTaak().getTaakSoort());

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", bezorgdOp.plusDays(1));

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(TaakSoort.VERZENDEN, enigeTaak().getTaakSoort(), "de vaststeltaak is vervangen door de herverzending");
    }

    // Fout, dan toch bezorgd, dan opnieuw een fout binnen de termijn: de herverzendtaak van de eerste fout
    // staat nog open en de tweede zou er anders op de unieke index mee botsen.
    @Test
    void tijdelijkeFoutNaBezorgingMetOpenHerverzending_vervangtDeOudeHerverzendtaak() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime fout = clock.nu().minusMinutes(3);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", fout);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "delivered", fout.plusMinutes(1));
        assertEquals(NotificatieStatus.BEZORGD, notificatie(id).getStatus());

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", fout.plusMinutes(2));

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(TaakSoort.VERZENDEN, enigeTaak().getTaakSoort(), "één herverzendtaak; de oude is vervangen");
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
                clock.nu());

        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie(id).getStatus());
        assertEquals(PogingStatus.TIJDELIJK_MISLUKT, poging(eerste.getId()).getStatus());
        assertTrue(taken().stream().noneMatch(t -> t.getTaakSoort() == TaakSoort.VERZENDEN));
    }

    @Test
    void tijdelijkeFoutNaEenPermanenteFout_plantGeenHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime nu = clock.nu();
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "permanent-failure", nu.minusMinutes(1));

        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", nu);

        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie(id).getStatus());
        assertEquals(List.of(TaakSoort.WISSEN), taken().stream().map(Taak::getTaakSoort).toList(), "alleen de wistaak van de eindstatus");
    }

    // Kan de herverzending haar poging nooit aanmaken, dan krijgt de notificatie een eindstatus en plant de
    // controletaak haar niet elke ronde opnieuw.
    @Test
    void uitgeputteHerverzendtaakZonderPoging_leidtTotTechnischMislukt() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                clock.nu());
        Taak herverzending = enigeTaak();

        QuarkusTransaction.requiringNew().run(() -> verzendTaakHandler.handelUitputtingAf(herverzending));

        assertEquals(NotificatieStatus.TECHNISCH_MISLUKT, notificatie(id).getStatus());
        assertEquals(Reden.TECHNISCH, notificatie(id).getReden());
        assertEquals(1, laatstePoging(id).getNummer());
    }

    @Test
    void controletaak_plantDeOntbrekendeHerverzendingNaDeWachttijd() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        OffsetDateTime fout = clock.nu();
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure", fout);
        // De controle draait later dan de fout: de wachttijd telt vanaf de fout.
        clock.zet(fout.plusMinutes(10).toInstant());
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            taakRepository.persist(new Taak(TaakSoort.CONTROLE, null, null, clock.nu(), null, null));
        });

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        Taak herverzending = taken().stream()
                .filter(t -> t.getTaakSoort() == TaakSoort.VERZENDEN && id.equals(t.getNotificatieId()))
                .findFirst().orElseThrow();
        assertEquals(fout.plusHours(1).toInstant(), herverzending.getDue().toInstant(), "herverzend-wachttijd vanaf de fout");
    }

    @Test
    void aanname_legtBerichttypeEnGeldigTotVast() {
        OffsetDateTime voor = clock.nu().truncatedTo(ChronoUnit.MICROS);

        UUID id = aannameService.neemAan(opdracht());

        Notificatie notificatie = notificatie(id);
        assertEquals(BerichtType.DEMO_TEMPLATE.name(), notificatie.getBerichtType());
        assertEquals(notificatie.getAangenomenOp().plusDays(7), notificatie.getGeldigTot(), "aanname plus zeven dagen");
        assertEquals(voor.plusDays(7).toInstant(), notificatie.getGeldigTot().toInstant());
    }

    // Een notificatie op verzonden, met een lopende eerste poging en haar navraagtaak.
    private UUID verzonden() {
        UUID id = aannameService.neemAan(opdracht());
        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);
        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());

        return id;
    }

    // Een notificatie met een tweede, lopende poging na een tijdelijke fout op de eerste.
    private UUID naHerverzending() {
        UUID id = verzonden();
        Poging eerste = laatstePoging(id);
        receiptVerwerker.verwerk(eerste.getNotifyId(), eerste.getId().toString(), "temporary-failure",
                clock.nu().minusMinutes(1));
        maakDue();
        TaakRonde.draai(taakWorker, TaakSoort.VERZENDEN);
        assertEquals(2, laatstePoging(id).getNummer());

        return id;
    }

    private static AannameOpdracht opdracht() {
        return opdracht(BerichtType.DEMO_TEMPLATE);
    }

    private static AannameOpdracht opdracht(BerichtType berichtType) {
        return new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email("burger@example.nl"), null, null,
                berichtType, Map.of());
    }

    private void verzetGeldigTot(UUID id) {
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verzetGeldigTot(taakRepository.getEntityManager(), id,
                clock.nu().minus(Duration.ofMinutes(1))));
    }

    private void maakDue() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetDue(taakRepository.getEntityManager(),
                TaakSoort.VERZENDEN, clock.nu().minusSeconds(1)));
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
        return QuarkusTransaction.requiringNew().call(() -> NotificatieFixtures.pogingen(pogingRepository.getEntityManager(),
                notificatieId));
    }

    private List<Taak> taken() {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.listAll());
    }

    private Taak enigeTaak() {
        List<Taak> taken = taken();
        assertEquals(1, taken.size(), taken.stream().map(Taak::getTaakSoort).toList().toString());

        return taken.getFirst();
    }

    private List<NotificatieStatus> overgangen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList());
    }
}
