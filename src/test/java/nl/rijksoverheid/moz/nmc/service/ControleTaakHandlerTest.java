package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        UUID eindstatus = notificatie(NotificatieStatus.NIET_BEZORGBAAR, UUID.randomUUID());

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.CONTROLE));

        Map<UUID, Taak> perNotificatie = perNotificatie();
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(aangenomen).getTaakSoort());
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(inVerzending).getTaakSoort());
        assertEquals(TaakSoort.RECONCILIEREN, perNotificatie.get(verzondenLopend).getTaakSoort());
        assertEquals(pogingRepository.findLaatsteVan(verzondenLopend).orElseThrow().getId().toString(),
                perNotificatie.get(verzondenLopend).getPayload().get(VerzendTaakHandler.PAYLOAD_POGING_ID));
        assertFalse(perNotificatie.containsKey(verzondenZonderPoging), "zonder herverzending valt er niets te plannen");
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, perNotificatie.get(bezorgd).getTaakSoort());
        assertFalse(perNotificatie.containsKey(eindstatus), "een notificatie met een eindstatus krijgt geen taak");
        assertEquals(NotificatieFixtures.DV_ID, perNotificatie.get(aangenomen).getDvId());

        Taak controle = QuarkusTransaction.requiringNew().call(() -> taakRepository
                .find(Taak_.TAAK_SOORT, TaakSoort.CONTROLE).singleResult());
        assertEquals(TaakStatus.OPEN, controle.getStatus());
        assertTrue(controle.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)), "opnieuw gepland");
        assertEquals(0, controle.getPogingen());
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
            TaakFixtures.markeerMislukt(taakRepository.getEntityManager(), TaakSoort.VERZENDEN, mislukt.getId());
        });

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(3, QuarkusTransaction.requiringNew().call(() -> taakRepository.count()),
                "controletaak plus de twee bestaande taken, geen nieuwe");
    }

    @Test
    void geplandeRondeControle_voertDeHandlerUit() {
        UUID id = notificatie(NotificatieStatus.AANGENOMEN, null);

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(TaakSoort.VERZENDEN, perNotificatie().get(id).getTaakSoort());
    }

    // Een notificatie in de gegeven status, zonder taak; bij een NotifyNL-id met een verzonden poging.
    private UUID notificatie(NotificatieStatus status, UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, null);
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

    private Map<UUID, Taak> perNotificatie() {
        List<Taak> taken = QuarkusTransaction.requiringNew().call(() -> taakRepository.listAll());
        Map<UUID, Taak> map = new HashMap<>();

        for (Taak taak : taken) {
            if (taak.getNotificatieId() != null) {
                map.put(taak.getNotificatieId(), taak);
            }
        }

        return map;
    }
}
