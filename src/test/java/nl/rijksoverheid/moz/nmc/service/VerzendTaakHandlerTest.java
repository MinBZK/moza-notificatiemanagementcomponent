package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.GetMessageDataApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetMultipleMessagesResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.GetOneMessageResponse;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailRequest;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class VerzendTaakHandlerTest {

    private static final String TEMPLATE_ID = "test-template-id";
    private static final String EMAIL = "burger@example.nl";

    @InjectMock
    @RestClient
    ProfielApi profielApi;

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
    void decentraal_happyFlow_verzondenMetPogingNavraagtaakEnAfgerondeVerzendtaak() {
        UUID notifyId = UUID.randomUUID();
        when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(notifyId.toString()));
        UUID id = aannameService.neemAan(decentraal());

        assertEquals(1, taakWorker.verwerk(TaakSoort.VERZENDEN));

        Notificatie notificatie = notificatie(id);
        Poging poging = poging(id);
        assertEquals(NotificatieStatus.VERZONDEN, notificatie.getStatus());
        assertEquals(2, notificatie.getVersie());
        assertEquals(PogingStatus.VERZONDEN, poging.getStatus());
        assertEquals(notifyId, poging.getNotifyId());
        assertNotNull(poging.getVerzondenOp());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN), overgangen(id));

        ArgumentCaptor<SendEmailRequest> verzoek = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sendAMessageApi).sendEmail(verzoek.capture());
        assertEquals(EMAIL, verzoek.getValue().getEmailAddress());
        assertEquals(TEMPLATE_ID, verzoek.getValue().getTemplateId());
        assertEquals(poging.getId().toString(), verzoek.getValue().getReference(), "reference is het poging-id");
        assertEquals("Voorbeeld BV", verzoek.getValue().getPersonalisation().get("naam"));

        List<Taak> taken = taken();
        assertEquals(1, taken.size(), "de verzendtaak is afgerond, de navraagtaak staat");
        assertEquals(TaakSoort.RECONCILIEREN, taken.getFirst().getSoort());
        assertEquals(id, taken.getFirst().getNotificatieId());
        assertTrue(taken.getFirst().getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30)));
        assertEquals(poging.getId().toString(), taken.getFirst().getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID));
    }

    @Test
    void centraal_happyFlow_haaltHetAdresOpInDeVerzendtaakEnBewaartHetNiet() {
        when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(partijMetEmail(EMAIL));
        when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
        UUID id = aannameService.neemAan(centraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        ArgumentCaptor<SendEmailRequest> verzoek = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sendAMessageApi).sendEmail(verzoek.capture());
        assertEquals(EMAIL, verzoek.getValue().getEmailAddress());
    }

    // Een onbekende partij of een partij zonder adres is een uitkomst van de notificatie, geen fout
    // van de taak.
    @Test
    void centraal_partijNietGevonden_eindigtInNietBezorgbaarMetGeenContactgegevens() {
        when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.NOT_FOUND).build()));
        UUID id = aannameService.neemAan(centraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Notificatie notificatie = notificatie(id);
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie.getStatus());
        assertEquals(Reden.GEEN_CONTACTGEGEVENS, notificatie.getReden());
        assertEquals(List.of(), taken(), "de verzendtaak is afgerond, er komt geen navraag");
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    @Test
    void centraal_partijZonderEmailadres_eindigtInNietBezorgbaar() {
        when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(new PartijResponse().partijId(UUID.randomUUID()));
        UUID id = aannameService.neemAan(centraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(Reden.GEEN_CONTACTGEGEVENS, notificatie(id).getReden());
    }

    // Een storing bij de Profielservice stelt de taak uit zonder poging; de notificatie blijft op
    // in-verzending met de geplande poging, die de volgende ronde hergebruikt.
    @Test
    void centraal_profielserviceStoring_steltUitZonderPoging() {
        when(profielApi.apiProfielserviceV1PartijPost(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()));
        UUID id = aannameService.neemAan(centraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.IN_VERZENDING, notificatie(id).getStatus());
        assertEquals(PogingStatus.GEPLAND, poging(id).getStatus());
        Taak taak = taken().getFirst();
        assertEquals(TaakSoort.VERZENDEN, taak.getSoort());
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertEquals(0, taak.getPogingen(), "een storing bij een externe dienst kost geen poging");
        assertNull(taak.getLeaseTot());
        assertTrue(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    // Een verbindingsfout of time-out is ook een storing en geen fout in het NMC: geen poging.
    @Test
    void centraal_profielserviceNietBereikbaar_steltUitZonderPoging() {
        when(profielApi.apiProfielserviceV1PartijPost(any())).thenThrow(new ProcessingException("connection refused"));
        aannameService.neemAan(centraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Taak taak = taken().getFirst();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertEquals(0, taak.getPogingen());
    }

    // De controletaak plant een verzendtaak zonder payload; de verzendgegevens komen van de notificatie.
    @Test
    void verzendtaakZonderPayload_verstuurtMetDeGegevensVanDeNotificatie() {
        when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
        UUID id = aannameService.neemAan(decentraal());
        UUID dvId = notificatie(id).getDvId();
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            taakRepository.persist(new Taak(TaakSoort.VERZENDEN, dvId, id, OffsetDateTime.now(ZoneOffset.UTC), null, null));
        });

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        ArgumentCaptor<SendEmailRequest> verzoek = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sendAMessageApi).sendEmail(verzoek.capture());
        assertEquals(TEMPLATE_ID, verzoek.getValue().getTemplateId());
    }

    // Een uitgeputte verzendtaak (%test: max-pogingen=2) zet de notificatie op technisch-mislukt, zodat
    // de Dienstverlener een eindstatus krijgt, en de taak verdwijnt.
    @Test
    void uitgeputteVerzendtaak_eindigtInTechnischMislukt() {
        UUID id = aannameService.neemAan(decentraal());
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE notificatie SET template_id = NULL WHERE id = ?1").setParameter(1, id).executeUpdate());

        taakWorker.verwerk(TaakSoort.VERZENDEN);
        zetDue(taken().getFirst(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Notificatie notificatie = notificatie(id);
        assertEquals(NotificatieStatus.TECHNISCH_MISLUKT, notificatie.getStatus());
        assertEquals(Reden.TECHNISCH, notificatie.getReden());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.TECHNISCH_MISLUKT),
                overgangen(id));
        assertEquals(List.of(), taken());
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    @Test
    void notifyWeigertHetAdres_eindigtInNietBezorgbaarMetOnbereikbaar() {
        when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build()));
        UUID id = aannameService.neemAan(decentraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Notificatie notificatie = notificatie(id);
        assertEquals(NotificatieStatus.NIET_BEZORGBAAR, notificatie.getStatus());
        assertEquals(Reden.ONBEREIKBAAR, notificatie.getReden());
        assertEquals(List.of(), taken());
    }

    // Een storing bij NotifyNL kost de notificatie geen poging; bij de herclaim wordt eerst op
    // reference gezocht, zodat een al aangeboden e-mail niet nog eens gaat.
    @Test
    void notifyStoring_steltUit_enHerclaimZoektEerstOpReference() {
        when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new WebApplicationException(Response.status(Response.Status.SERVICE_UNAVAILABLE).build()));
        UUID id = aannameService.neemAan(decentraal());
        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Taak uitgesteld = taken().getFirst();
        assertEquals(0, uitgesteld.getPogingen());
        assertEquals(NotificatieStatus.IN_VERZENDING, notificatie(id).getStatus());
        Poging poging = poging(id);

        // De e-mail bleek toch aangeboden: NotifyNL kent de reference.
        UUID notifyId = UUID.randomUUID();
        when(getMessageDataApi.getMultipleMessagesStatus(isNull(), isNull(), eq(poging.getId().toString()), isNull(), isNull()))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of(
                        new GetOneMessageResponse().id(notifyId.toString()).reference(poging.getId().toString()))));
        zetDue(uitgesteld, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(notifyId, poging(id).getNotifyId());
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> pogingRepository.count()), "dezelfde poging");
        verify(sendAMessageApi, times(1)).sendEmail(any());
    }

    @Test
    void notifyNietBereikbaar_steltUit_enHerclaimZonderTrefferVerstuurtOpnieuwMetDezelfdePoging() {
        when(sendAMessageApi.sendEmail(any()))
                .thenThrow(new ProcessingException("connection timed out"))
                .thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
        when(getMessageDataApi.getMultipleMessagesStatus(any(), any(), any(), any(), any()))
                .thenReturn(new GetMultipleMessagesResponse().notifications(List.of()));
        UUID id = aannameService.neemAan(decentraal());
        taakWorker.verwerk(TaakSoort.VERZENDEN);
        zetDue(taken().getFirst(), OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
        assertEquals(1, poging(id).getNummer());
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> pogingRepository.count()));
        verify(sendAMessageApi, times(2)).sendEmail(any());
    }

    // De receipt is er eerder dan de verzend-commit: de poging heeft het id al en de notificatie is
    // al bezorgd. De worker overschrijft dat niet en plant geen navraag meer.
    @Test
    void receiptVoorDeVerzendCommit_wordtNietOverschreven() {
        UUID notifyId = UUID.randomUUID();
        when(sendAMessageApi.sendEmail(any())).thenAnswer(aanroep -> {
            SendEmailRequest verzoek = aanroep.getArgument(0);
            receiptVerwerker.verwerk(notifyId, verzoek.getReference(), "delivered", OffsetDateTime.now(ZoneOffset.UTC));

            return new SendEmailResponse().id(notifyId.toString());
        });
        UUID id = aannameService.neemAan(decentraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        Notificatie notificatie = notificatie(id);
        Poging poging = poging(id);
        assertEquals(NotificatieStatus.BEZORGD, notificatie.getStatus());
        assertEquals(PogingStatus.BEZORGD, poging.getStatus());
        assertEquals(notifyId, poging.getNotifyId());
        assertEquals(List.of(), poging.getDuplicaatIds());
        assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN,
                NotificatieStatus.BEZORGD), overgangen(id));
        assertEquals(List.of(), taken(), "geen navraag voor een al bezorgde notificatie");
    }

    @Test
    void notificatieInmiddelsGeannuleerd_rondtDeTaakAfZonderVerzending() {
        UUID id = aannameService.neemAan(decentraal());
        QuarkusTransaction.requiringNew().run(() -> overgangsfunctie.voerUit(id, NotificatieStatus.GEANNULEERD, Reden.GEANNULEERD));

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.GEANNULEERD, notificatie(id).getStatus());
        assertEquals(List.of(), taken());
        verify(sendAMessageApi, never()).sendEmail(any());
    }

    @Test
    void geplandeRondeVerzenden_voertDeHandlerUit() {
        when(sendAMessageApi.sendEmail(any())).thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
        UUID id = aannameService.neemAan(decentraal());

        taakWorker.verwerk(TaakSoort.VERZENDEN);

        assertEquals(NotificatieStatus.VERZONDEN, notificatie(id).getStatus());
    }

    private static AannameOpdracht decentraal() {
        return new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email(EMAIL), null, null, TEMPLATE_ID,
                Map.of("naam", "Voorbeeld BV"), null);
    }

    private static AannameOpdracht centraal() {
        return new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.KVK, "12345678"), "Gemeente Voorbeeld",
                "Parkeervergunning", TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), null);
    }

    private static PartijResponse partijMetEmail(String email) {
        return new PartijResponse().partijId(UUID.randomUUID()).contactgegevens(List.of(
                new ContactgegevenResponse().type(ContactgegevenResponse.TypeEnum.EMAIL).waarde(email).isDefault(true)));
    }

    private Notificatie notificatie(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(id));
    }

    private Poging poging(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> pogingRepository.findLaatsteVan(notificatieId).orElseThrow());
    }

    private List<Taak> taken() {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.listAll());
    }

    private List<NotificatieStatus> overgangen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() ->
                eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList());
    }

    private void zetDue(Taak taak, OffsetDateTime due) {
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE taak SET due = ?2 WHERE id = ?1")
                .setParameter(1, taak.getId()).setParameter(2, due).executeUpdate());
    }
}
