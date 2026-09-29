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
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ControleTaakHandlerTest {

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
            eventRepository.deleteAll();
            notificatieRepository.deleteAll();
            taakRepository.persist(new Taak(TaakSoort.CONTROLE, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null));
        });
    }

    @Test
    void controle_plantDeOntbrekendeTaakPerStatus_enPlantZichzelfOpnieuw() {
        UUID aangenomen = notificatie(NotificatieStatus.AANGENOMEN, null);
        UUID inVerzending = notificatie(NotificatieStatus.IN_VERZENDING, null);
        UUID verzondenLopend = notificatie(NotificatieStatus.VERZONDEN, UUID.randomUUID());
        UUID verzondenZonderPoging = notificatie(NotificatieStatus.VERZONDEN, null);
        UUID bezorgd = notificatie(NotificatieStatus.BEZORGD, UUID.randomUUID());
        UUID terminaal = notificatie(NotificatieStatus.NIET_BEZORGBAAR, UUID.randomUUID());

        assertEquals(1, taakWorker.verwerk(TaakSoort.CONTROLE));

        Map<UUID, Taak> perNotificatie = perNotificatie();
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(aangenomen).getSoort());
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(inVerzending).getSoort());
        assertEquals(TaakSoort.RECONCILIEREN, perNotificatie.get(verzondenLopend).getSoort());
        assertEquals(pogingRepository.findLaatsteVan(verzondenLopend).orElseThrow().getId().toString(),
                perNotificatie.get(verzondenLopend).getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID));
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(verzondenZonderPoging).getSoort(),
                "een geplande poging zonder NotifyNL-id is een herverzending in uitvoering");
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, perNotificatie.get(bezorgd).getSoort());
        assertTrue(!perNotificatie.containsKey(terminaal), "een terminale notificatie krijgt geen taak van de controletaak");
        assertEquals(NotificatieFixtures.DV_ID, perNotificatie.get(aangenomen).getDvId());

        Taak controle = QuarkusTransaction.requiringNew().call(() -> taakRepository
                .find("soort", TaakSoort.CONTROLE).singleResult());
        assertEquals(TaakStatus.OPEN, controle.getStatus());
        assertTrue(controle.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)), "opnieuw gepland");
        assertEquals(0, controle.getPogingen());
    }

    // Opnieuw gepland houdt de vaststeltaak de termijn vanaf de bezorging aan.
    @Test
    void controle_bezorgdMetReceipt_plantDeVaststeltaakNaDeTermijn() {
        UUID id = notificatie(NotificatieStatus.BEZORGD, UUID.randomUUID());
        OffsetDateTime bezorgdOp = OffsetDateTime.parse("2030-01-01T10:00:00Z");
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.findLaatsteVan(id).orElseThrow()
                .verwerkReceipt(PogingStatus.BEZORGD, bezorgdOp));

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(bezorgdOp.plusDays(8).toInstant(), perNotificatie().get(id).getDue().toInstant());
    }

    // Zonder bekend bezorgtijdstip loopt de termijn vanaf nu, zodat de taak nooit te vroeg vaststelt.
    @Test
    void controle_bezorgdZonderBezorgtijdstip_plantDeVaststeltaakEenVolleTermijnVooruit() {
        UUID id = notificatie(NotificatieStatus.BEZORGD, UUID.randomUUID());

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertTrue(perNotificatie().get(id).getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7)));
    }

    // De controletaak plant zichzelf zijn hele levensduur opnieuw; fouten verspreid over die tijd mogen
    // niet optellen tot mislukt.
    @Test
    void controle_geslaagdeRonde_zetDePogingenTerug() {
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE taak SET pogingen = 1 WHERE soort = 'CONTROLE'").executeUpdate());

        taakWorker.verwerk(TaakSoort.CONTROLE);

        Taak controle = QuarkusTransaction.requiringNew().call(() -> taakRepository.find("soort", TaakSoort.CONTROLE).singleResult());
        assertEquals(0, controle.getPogingen());
        assertEquals(TaakStatus.OPEN, controle.getStatus());
    }

    @Test
    void controle_laatNotificatiesMetEenOpenOfMislukteTaakMetRust() {
        UUID metOpenTaak = notificatie(NotificatieStatus.AANGENOMEN, null);
        UUID metMislukteTaak = notificatie(NotificatieStatus.AANGENOMEN, null);
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.persist(new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, metOpenTaak,
                    OffsetDateTime.now(ZoneOffset.UTC).plusHours(1), null, null));
            Taak mislukt = new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, metMislukteTaak,
                    OffsetDateTime.now(ZoneOffset.UTC), null, null);
            taakRepository.persist(mislukt);
            taakRepository.flush();
            taakRepository.getEntityManager().createNativeQuery("UPDATE taak SET status = 'MISLUKT' WHERE id = ?1")
                    .setParameter(1, mislukt.getId()).executeUpdate();
        });

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(3, QuarkusTransaction.requiringNew().call(() -> taakRepository.count()),
                "controletaak plus de twee bestaande taken, geen nieuwe");
    }

    @AfterEach
    void zonderWebhook() {
        zetWebhook(null);
    }

    // Een webhook komt er via het register bij; de controletaak geeft die dienstverlener zijn
    // terugkoppeltaak, en een tweede ronde plant er geen tweede.
    @Test
    void controle_plantEenTerugkoppeltaakVoorEenDienstverlenerMetWebhook_eenmaal() {
        zetWebhook("https://dv.example.nl/webhook");

        taakWorker.verwerk(TaakSoort.CONTROLE);
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE taak SET due = now() WHERE soort = 'CONTROLE'").executeUpdate());
        taakWorker.verwerk(TaakSoort.CONTROLE);

        List<Taak> terugkoppeltaken = QuarkusTransaction.requiringNew().call(() -> taakRepository
                .find("soort", TaakSoort.TERUGKOPPELEN).list());
        assertEquals(1, terugkoppeltaken.size());
        assertEquals(NotificatieFixtures.DV_ID, terugkoppeltaken.getFirst().getDvId());
    }

    @Test
    void controle_zonderWebhook_plantGeenTerugkoppeltaak() {
        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.TERUGKOPPELEN)));
    }

    @Test
    void geplandeRondeControle_voertDeHandlerUit() {
        UUID id = notificatie(NotificatieStatus.AANGENOMEN, null);

        taakWorker.verwerk(TaakSoort.CONTROLE);

        assertEquals(TaakSoort.VERZENDEN, perNotificatie().get(id).getSoort());
    }

    private void zetWebhook(String url) {
        QuarkusTransaction.requiringNew().run(() -> taakRepository.getEntityManager()
                .createNativeQuery("UPDATE dienstverlener SET webhook_url = ?1 WHERE id = ?2")
                .setParameter(1, url).setParameter(2, NotificatieFixtures.DV_ID).executeUpdate());
    }

    // Een notificatie in de gegeven status, zonder taak; bij een NotifyNL-id met een verzonden poging.
    private UUID notificatie(NotificatieStatus status, UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID);
            overgangsfunctie.neemAan(notificatie);
            UUID id = notificatie.getId();

            if (status != NotificatieStatus.AANGENOMEN) {
                overgangsfunctie.voerUit(id, NotificatieStatus.IN_VERZENDING, null);
                Poging poging = new Poging(id, 1);
                pogingRepository.persist(poging);

                if (status != NotificatieStatus.IN_VERZENDING) {
                    if (notifyId != null) {
                        poging.markeerVerzonden(notifyId, OffsetDateTime.now(ZoneOffset.UTC));
                    }

                    overgangsfunctie.voerUit(id, NotificatieStatus.VERZONDEN, null);
                }

                if (status == NotificatieStatus.BEZORGD || status == NotificatieStatus.NIET_BEZORGBAAR) {
                    overgangsfunctie.voerUit(id, status, status == NotificatieStatus.BEZORGD ? null : Reden.ONBEREIKBAAR);
                }
            }

            return id;
        });
    }

    // Zonder de wistaak die de overgangsfunctie bij elke terminale status plant.
    private Map<UUID, Taak> perNotificatie() {
        List<Taak> taken = QuarkusTransaction.requiringNew().call(() -> taakRepository.list("soort != ?1", TaakSoort.WISSEN));
        java.util.Map<UUID, Taak> map = new java.util.HashMap<>();

        for (Taak taak : taken) {
            if (taak.getNotificatieId() != null) {
                map.put(taak.getNotificatieId(), taak);
            }
        }

        return map;
    }
}
