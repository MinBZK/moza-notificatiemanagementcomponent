package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
class AannameServiceTest {

    private static final String TEMPLATE_ID = "test-template-id";

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @Inject
    AannameService aannameService;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    Sleutelbeheer sleutelbeheer;

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

    @AfterEach
    void quotumWeg() {
        zetQuotum(null);
    }

    @Test
    void neemAan_centraal_slaatNotificatieEventEnVerzendtaakOpZonderExterneAanroep() {
        UUID id = aannameService.neemAan(centraal("https://omc.example.nl/callback"));

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = notificatieRepository.findById(id);
            assertEquals(NotificatieStatus.AANGENOMEN, notificatie.getStatus());
            assertEquals(0, notificatie.getVersie());
            assertEquals(NotificatieFixtures.DV_ID, notificatie.getDvId());
            assertEquals("https://omc.example.nl/callback", notificatie.getCallbackUrl());
            assertFalse(notificatie.getAangenomenOp().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
            assertEquals(new Ontvanger(Ontvanger.Soort.KVK, "12345678"),
                    sleutelbeheer.ontsleutelOntvanger(id, notificatie.getVersleuteldeGegevens()));
            assertEquals(Map.of("naam", "Voorbeeld BV"),
                    sleutelbeheer.ontsleutelPersonalisation(id, notificatie.getVersleuteldeGegevens()));

            List<Event> events = eventRepository.findByNotificatie(id);
            assertEquals(1, events.size());
            assertEquals(NotificatieStatus.AANGENOMEN, events.getFirst().getNaar());

            Taak taak = taakRepository.listAll().getFirst();
            assertEquals(TaakSoort.VERZENDEN, taak.getSoort());
            assertEquals(id, taak.getNotificatieId());
            assertEquals(NotificatieFixtures.DV_ID, taak.getDvId());
            assertFalse(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
            assertEquals(Map.of(), taak.getPayload(), "de verzendgegevens staan op de notificatie");
            assertEquals(TEMPLATE_ID, notificatie.getTemplateId());
            assertEquals("CENTRAAL", notificatie.getRegie());
            assertEquals("Gemeente Voorbeeld", notificatie.getDienstverlenerNaam());
            assertEquals("Parkeervergunning", notificatie.getDienst());
            assertNull(taak.getLeaseTot());
        });
        verifyNoInteractions(profielApi, sendAMessageApi);
    }

    @Test
    void neemAan_decentraal_bewaartHetAdresVersleuteldEnLaatDienstverlenerUitDePayload() {
        UUID id = aannameService.neemAan(new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email("burger@example.nl"),
                null, null, TEMPLATE_ID, null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = notificatieRepository.findById(id);
            assertEquals(Ontvanger.email("burger@example.nl"),
                    sleutelbeheer.ontsleutelOntvanger(id, notificatie.getVersleuteldeGegevens()));
            assertEquals(Map.of(), sleutelbeheer.ontsleutelPersonalisation(id, notificatie.getVersleuteldeGegevens()));

            assertEquals("DECENTRAAL", notificatie.getRegie());
            assertNull(notificatie.getDienstverlenerNaam());
        });
        verifyNoInteractions(profielApi, sendAMessageApi);
    }

    // Het quotum telt per dienstverlener per dag; zonder quotum in het register is er geen grens.
    @Test
    void neemAan_quotumBereikt_weigertEnLegtNietsVast() {
        zetQuotum(1);
        aannameService.neemAan(centraal(null));

        assertThrows(QuotumOverschredenException.class, () -> aannameService.neemAan(centraal(null)));

        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> taakRepository.count()));
    }

    @Test
    void neemAan_zonderQuotum_kentGeenGrens() {
        for (int i = 0; i < 3; i++) {
            aannameService.neemAan(centraal(null));
        }

        assertEquals(3, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    @Test
    void aannameOpdracht_zonderOntvanger_weigert() {
        assertThrows(NullPointerException.class,
                () -> new AannameOpdracht(Regie.DECENTRAAL, null, null, null, TEMPLATE_ID, null, null));
    }

    private static AannameOpdracht centraal(String callbackUrl) {
        return new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.KVK, "12345678"),
                "Gemeente Voorbeeld", "Parkeervergunning", TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), callbackUrl);
    }

    private void zetQuotum(Integer quotum) {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("UPDATE dienstverlener SET quotum_per_dag = ?1 WHERE id = ?2")
                    .setParameter(1, quotum)
                    .setParameter(2, NotificatieFixtures.DV_ID)
                    .executeUpdate();
            assertTrue(true);
        });
    }
}
