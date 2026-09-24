package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendAdapter;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLVerzendException;
import nl.rijksoverheid.moz.nmc.client.profielservice.GeenEmailadresGevondenException;
import nl.rijksoverheid.moz.nmc.client.profielservice.ProfielServiceAdapter;
import nl.rijksoverheid.moz.nmc.controller.IdentificatieType;
import nl.rijksoverheid.moz.nmc.domain.Event;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class NotificatieServiceTest {

    private static final String TEST_TEMPLATE_ID = "test-template-id";

    @InjectMock
    ProfielServiceAdapter profielServiceAdapter;

    @InjectMock
    NotifyNLVerzendAdapter verzendAdapter;

    @Inject
    NotificatieService service;

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

    @Test
    void versturen_happyFlow_staatOpVerzondenMetPogingEnDrieEvents() throws Exception {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        UUID notifyId = UUID.randomUUID();
        when(verzendAdapter.verstuurEmail(eq("burger@example.nl"), eq(TEST_TEMPLATE_ID), eq(Map.of("naam", "Voorbeeld BV"))))
                .thenReturn(notifyId);

        UUID id = service.versturen(opdracht("https://omc.example.nl/callback")).getId();

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = notificatieRepository.findById(id);
            Poging poging = pogingRepository.findByNotifyId(notifyId).orElseThrow();
            List<NotificatieStatus> overgangen = eventRepository.findByNotificatie(id).stream().map(Event::getNaar).toList();

            assertEquals(NotificatieStatus.VERZONDEN, notificatie.getStatus());
            assertEquals(2, notificatie.getVersie());
            assertEquals("https://omc.example.nl/callback", notificatie.getCallbackUrl());
            assertEquals(id, poging.getNotificatieId());
            assertEquals(PogingStatus.VERZONDEN, poging.getStatus());
            assertNotNull(poging.getVerzondenOp());
            assertEquals(List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.IN_VERZENDING, NotificatieStatus.VERZONDEN),
                    overgangen);
        });
    }

    // Notificatie, poging en in-verzending staan geflusht in de database voordat NotifyNL wordt
    // aangeroepen, zodat een schrijffout geen verstuurde e-mail zonder record oplevert.
    @Test
    void versturen_legtVastVoordatNotifyWordtAangeroepen() throws Exception {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        when(verzendAdapter.verstuurEmail(any(), any(), any())).thenAnswer(aanroep -> {
            Object status = notificatieRepository.getEntityManager()
                    .createNativeQuery("SELECT n.status FROM notificatie n JOIN poging p ON p.notificatie_id = n.id "
                            + "WHERE p.status = 'GEPLAND'")
                    .getSingleResult();
            assertEquals("IN_VERZENDING", status);

            return UUID.randomUUID();
        });

        service.versturen(opdracht(null));
    }

    @Test
    void versturen_notifyFaalt_gooitEnLaatNietsAchter() throws Exception {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenReturn("burger@example.nl");
        when(verzendAdapter.verstuurEmail(any(), any(), any())).thenThrow(new NotifyNLVerzendException("NotifyNL gaf status 500 terug"));

        assertThrows(NotificatieException.class, () -> service.versturen(opdracht(null)));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
    }

    @Test
    void versturen_geenEmailadresGevonden_legtNietsVastEnRoeptNotifyNietAan() {
        when(profielServiceAdapter.zoekEmailAdres(any())).thenThrow(new GeenEmailadresGevondenException("geen e-mailadres"));

        assertThrows(GeenEmailadresGevondenException.class, () -> service.versturen(opdracht(null)));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count()));
        verifyNoInteractions(verzendAdapter);
    }

    @Test
    void verstuurDecentraal_happyFlow_verstuurtNaarOpgegevenEmailZonderProfielserviceLookup() throws Exception {
        UUID notifyId = UUID.randomUUID();
        when(verzendAdapter.verstuurEmail(eq("burger@example.nl"), eq(TEST_TEMPLATE_ID), eq(Map.of("naam", "Voorbeeld BV"))))
                .thenReturn(notifyId);

        Notificatie resultaat = service.verstuurDecentraal(new DecentraleNotificatieVersturenOpdracht(
                "burger@example.nl", TEST_TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), "https://omc.example.nl/callback"));

        assertEquals(NotificatieStatus.VERZONDEN, resultaat.getStatus());
        assertEquals(resultaat.getId(),
                QuarkusTransaction.requiringNew().call(() -> pogingRepository.findByNotifyId(notifyId).orElseThrow().getNotificatieId()));
        verifyNoInteractions(profielServiceAdapter);
    }

    private NotificatieVersturenOpdracht opdracht(String callbackUrl) {
        return new NotificatieVersturenOpdracht(IdentificatieType.KVK, "12345678",
                "Gemeente Voorbeeld", "Parkeervergunning", TEST_TEMPLATE_ID, Map.of("naam", "Voorbeeld BV"), callbackUrl);
    }
}
