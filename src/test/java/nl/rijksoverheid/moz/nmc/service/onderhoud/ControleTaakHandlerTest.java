package nl.rijksoverheid.moz.nmc.service.onderhoud;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.config.ControleConfig;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak_;
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.service.verzending.VerzendTaakHandler;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import nl.rijksoverheid.moz.nmc.testhelper.VerstelbareClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import nl.rijksoverheid.moz.nmc.service.taak.TaakUitkomst;
import nl.rijksoverheid.moz.nmc.service.NotificatieNietGevondenException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ControleTaakHandlerTest {

    // De klok staat stil, zodat de test exacte tijdstippen kan verwachten.
    private final VerstelbareClock clock = VerstelbareClock.opNu();

    @Inject
    TaakWorker taakWorker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    TaakRepository taakRepository;

    @BeforeEach
    void setUp() {
        QuarkusMock.installMockForType(clock, Clock.class);
        TestDatabase.leeg();
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.persist(new Taak(TaakSoort.CONTROLE, null, null, clock.nu(), null, null));
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
        assertEquals(TaakSoort.VERZENDEN, perNotificatie.get(verzondenZonderPoging).getTaakSoort(),
                "een geplande poging zonder NotifyNL-id is een herverzending in uitvoering");
        assertEquals(TaakSoort.BEZORGING_VASTSTELLEN, perNotificatie.get(bezorgd).getTaakSoort());
        assertFalse(perNotificatie.containsKey(eindstatus), "een notificatie met een eindstatus krijgt geen taak");
        assertEquals(NotificatieFixtures.DV_ID, perNotificatie.get(aangenomen).getDvId());

        Taak controle = QuarkusTransaction.requiringNew().call(() -> taakRepository
                .find(Taak_.TAAK_SOORT, TaakSoort.CONTROLE).singleResult());
        assertEquals(TaakStatus.OPEN, controle.getStatus());
        assertEquals(clock.nu().plusMinutes(5).toInstant(), controle.getDue().toInstant(), "opnieuw gepland op het interval");
        assertEquals(0, controle.getPogingen());
    }

    // Geen taak kan haar nog verder brengen, dus ze krijgt een eindstatus; daarmee volgen de wistaak en
    // het opruimen.
    @Test
    void controle_verzondenZonderLopendeOfHerverzendbarePoging_eindigtInTechnischMislukt() {
        UUID id = notificatie(NotificatieStatus.VERZONDEN, UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.findLaatsteVan(id).orElseThrow()
                .verwerkReceipt(PogingStatus.PERMANENT_MISLUKT, clock.nu()));

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        Notificatie notificatie = LeesHelper.notificatie(id);
        assertEquals(NotificatieStatus.TECHNISCH_MISLUKT, notificatie.getStatus());
        assertEquals(Reden.TECHNISCH, notificatie.getReden());
        assertFalse(perNotificatie().containsKey(id), "geen andere taak dan de wistaak");
        assertEquals(1, QuarkusTransaction.requiringNew().call(() -> taakRepository.count(
                Taak_.TAAK_SOORT + " = ?1 AND " + Taak_.NOTIFICATIE_ID + " = ?2", TaakSoort.WISSEN, id)));
    }

    // Een navraag op een onbekende poging rondt zich af zonder iets te doen; zonder deze overgang plant de
    // controletaak er elke ronde opnieuw een.
    @Test
    void controle_verzondenMetOnbekendePoging_gaatNaarBezorgstatusOnbekend() {
        UUID id = notificatie(NotificatieStatus.VERZONDEN, UUID.randomUUID());
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.findLaatsteVan(id).orElseThrow().markeerOnbekend());

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        Notificatie notificatie = LeesHelper.notificatie(id);
        assertEquals(NotificatieStatus.BEZORGSTATUS_ONBEKEND, notificatie.getStatus());
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> taakRepository.count(
                Taak_.TAAK_SOORT + " = ?1 AND " + Taak_.NOTIFICATIE_ID + " = ?2", TaakSoort.RECONCILIEREN, id)));
    }

    // Opnieuw gepland houdt de vaststeltaak de termijn vanaf de bezorging aan.
    @Test
    void controle_bezorgdMetReceipt_plantDeVaststeltaakNaDeTermijn() {
        UUID id = notificatie(NotificatieStatus.BEZORGD, UUID.randomUUID());
        OffsetDateTime bezorgdOp = OffsetDateTime.parse("2030-01-01T10:00:00Z");
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.findLaatsteVan(id).orElseThrow()
                .verwerkReceipt(PogingStatus.BEZORGD, bezorgdOp));

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(bezorgdOp.plusDays(8).toInstant(), perNotificatie().get(id).getDue().toInstant());
    }

    // Zonder bekend bezorgtijdstip loopt de termijn vanaf nu, zodat de taak nooit te vroeg vaststelt.
    @Test
    void controle_bezorgdZonderBezorgtijdstip_plantDeVaststeltaakEenVolleTermijnVooruit() {
        UUID id = notificatie(NotificatieStatus.BEZORGD, UUID.randomUUID());

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(clock.nu().plusDays(8).toInstant(), perNotificatie().get(id).getDue().toInstant());
    }

    // De controletaak plant zichzelf zijn hele levensduur opnieuw; fouten verspreid over die tijd mogen
    // niet optellen tot mislukt.
    @Test
    void controle_geslaagdeRonde_zetDePogingenTerug() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetPogingen(taakRepository.getEntityManager(),
                TaakSoort.CONTROLE, taakRepository.find(Taak_.TAAK_SOORT, TaakSoort.CONTROLE).singleResult().getId(), 1));

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        Taak controle = LeesHelper.enigeTaak(TaakSoort.CONTROLE);
        assertEquals(0, controle.getPogingen());
        assertEquals(TaakStatus.OPEN, controle.getStatus());
    }

    @Test
    void controle_laatNotificatiesMetEenOpenOfMislukteTaakMetRust() {
        UUID metOpenTaak = notificatie(NotificatieStatus.AANGENOMEN, null);
        UUID metMislukteTaak = notificatie(NotificatieStatus.AANGENOMEN, null);
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.persist(new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, metOpenTaak,
                    clock.nu().plusHours(1), null, null));
            Taak mislukt = new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, metMislukteTaak,
                    clock.nu(), null, null);
            taakRepository.persist(mislukt);
            taakRepository.flush();
            TaakFixtures.markeerMislukt(taakRepository.getEntityManager(), TaakSoort.VERZENDEN, mislukt.getId());
        });

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(3, LeesHelper.aantalTaken(),
                "controletaak plus de twee bestaande taken, geen nieuwe");
    }

    // Na geldig_tot houdt een mislukte taak de notificatie niet meer tegen: ze krijgt de taak die bij haar
    // status hoort en loopt zo naar een eindstatus, zodat de wistaak en het opruimen er nog aan toekomen.
    @Test
    void controle_mislukteTaakNaGeldigTot_plantDeOntbrekendeTaakAlsnog() {
        UUID id = notificatie(NotificatieStatus.AANGENOMEN, null);
        QuarkusTransaction.requiringNew().run(() -> {
            Taak mislukt = new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, id, clock.nu(), null, null);
            taakRepository.persist(mislukt);
            taakRepository.flush();
            TaakFixtures.markeerMislukt(taakRepository.getEntityManager(), TaakSoort.VERZENDEN, mislukt.getId());
            NotificatieFixtures.verzetGeldigTot(taakRepository.getEntityManager(), id, clock.nu().minusMinutes(1));
        });

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(1L, QuarkusTransaction.requiringNew().call(() -> taakRepository.count(Taak_.TAAK_SOORT + " = ?1 AND "
                + Taak_.NOTIFICATIE_ID + " = ?2 AND " + Taak_.STATUS + " = ?3", TaakSoort.VERZENDEN, id, TaakStatus.OPEN)),
                "een nieuwe verzendtaak naast de mislukte");
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

        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetDue(taakRepository.getEntityManager(),
                TaakSoort.CONTROLE, taakRepository.find(Taak_.TAAK_SOORT, TaakSoort.CONTROLE).singleResult().getId(),
                clock.nu()));
        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        List<Taak> terugkoppeltaken = QuarkusTransaction.requiringNew().call(() -> taakRepository
                .find(Taak_.TAAK_SOORT, TaakSoort.TERUGKOPPELEN).list());
        assertEquals(1, terugkoppeltaken.size());
        assertEquals(NotificatieFixtures.DV_ID, terugkoppeltaken.getFirst().getDvId());
    }

    @Test
    void controle_zonderWebhook_plantGeenTerugkoppeltaak() {
        TaakRonde.draai(taakWorker, TaakSoort.CONTROLE);

        assertEquals(0, LeesHelper.aantalTaken(TaakSoort.TERUGKOPPELEN));
    }

    // Tussen de selectie en de vergrendeling kan een notificatie verdwijnen of een taak krijgen; de eerste
    // gooit een exception, de tweede valt onder de vergrendeling af. De ronde gaat door met de volgende.
    @Test
    void controle_notificatieNaDeSelectieVerwijderdOfVoorzien_slaatHaarOverEnHerstelDeVolgende() {
        UUID verwijderd = UUID.randomUUID();
        UUID voorzien = UUID.randomUUID();
        UUID zonderTaak = UUID.randomUUID();
        MetMocks mocks = new MetMocks();
        Notificatie voorzienMock = notificatieMock(voorzien);
        Notificatie zonderTaakMock = notificatieMock(zonderTaak);
        when(mocks.notificatieRepository.zonderTaak(any(), any(), anyInt())).thenReturn(List.of(verwijderd, voorzien, zonderTaak));
        when(mocks.overgangsfunctie.vergrendel(verwijderd)).thenThrow(new NotificatieNietGevondenException("weg"));
        when(mocks.overgangsfunctie.vergrendel(voorzien)).thenReturn(voorzienMock);
        when(mocks.overgangsfunctie.vergrendel(zonderTaak)).thenReturn(zonderTaakMock);
        when(mocks.notificatieRepository.isZonderTaak(eq(voorzien), any(), any())).thenReturn(false);
        when(mocks.notificatieRepository.isZonderTaak(eq(zonderTaak), any(), any())).thenReturn(true);
        when(mocks.pogingRepository.findLaatsteVan(zonderTaak)).thenReturn(Optional.empty());

        TaakUitkomst uitkomst = mocks.handler.voerUit(controletaak(), () -> {
        });

        ArgumentCaptor<Taak> gepland = ArgumentCaptor.forClass(Taak.class);
        verify(mocks.taakRepository).persist(gepland.capture());
        assertEquals(zonderTaak, gepland.getValue().getNotificatieId());
        assertEquals(TaakSoort.VERZENDEN, gepland.getValue().getTaakSoort());
        verify(mocks.overgangsfunctie, never()).voerUit(any(), any(), any());
        TaakUitkomst.Herpland herpland = assertInstanceOf(TaakUitkomst.Herpland.class, uitkomst);
        assertEquals(clock.nu().plusMinutes(5).toInstant(), herpland.due().toInstant());
    }

    // Een ronde bestaat uit losse transacties; de lease wordt na elke honderd notificaties verlengd.
    @ParameterizedTest
    @CsvSource({"0, 0", "99, 0", "100, 1", "199, 1", "200, 2"})
    void controle_veleNotificaties_verlengtDeLeasePerHonderd(int aantal, int verlengingen) {
        MetMocks mocks = new MetMocks();
        when(mocks.notificatieRepository.zonderTaak(any(), any(), anyInt()))
                .thenReturn(IntStream.range(0, aantal).mapToObj(i -> UUID.randomUUID()).toList());
        when(mocks.overgangsfunctie.vergrendel(any())).thenAnswer(aanroep -> notificatieMock(aanroep.getArgument(0)));
        AtomicInteger verlengd = new AtomicInteger();

        mocks.handler.voerUit(controletaak(), verlengd::incrementAndGet);

        assertEquals(verlengingen, verlengd.get());
    }

    // Een periodieke taak gaat na het maximum aantal pogingen niet op mislukt: niets maakt hem opnieuw aan.
    @Test
    void periodiek_isWaar() {
        assertTrue(new MetMocks().handler.periodiek());
    }

    /** De handler met gemockte repositories en overgangsfunctie, voor toestanden die alleen bij gelijktijdigheid ontstaan. */
    private final class MetMocks {

        final NotificatieRepository notificatieRepository = mock(NotificatieRepository.class);
        final PogingRepository pogingRepository = mock(PogingRepository.class);
        final TaakRepository taakRepository = mock(TaakRepository.class);
        final Overgangsfunctie overgangsfunctie = mock(Overgangsfunctie.class);
        final ControleTaakHandler handler;

        MetMocks() {
            ControleConfig config = mock(ControleConfig.class);
            when(config.interval()).thenReturn(Duration.ofMinutes(5));
            when(config.maxPerRonde()).thenReturn(1000);
            handler = new ControleTaakHandler(notificatieRepository, pogingRepository, taakRepository, overgangsfunctie,
                    null, null, clock, config);
        }
    }

    private static Notificatie notificatieMock(UUID id) {
        Notificatie notificatie = mock(Notificatie.class);
        when(notificatie.getId()).thenReturn(id);
        when(notificatie.getDvId()).thenReturn(NotificatieFixtures.DV_ID);
        when(notificatie.getStatus()).thenReturn(NotificatieStatus.AANGENOMEN);

        return notificatie;
    }

    private Taak controletaak() {
        return new Taak(TaakSoort.CONTROLE, null, null, clock.nu(), null, null);
    }

    private void zetWebhook(String url) {
        TaakFixtures.zetWebhook(url, null, null);
    }

    // Een notificatie in de gegeven status, zonder taak; bij een NotifyNL-id met een verzonden poging.
    private UUID notificatie(NotificatieStatus status, UUID notifyId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, clock.nu());
            overgangsfunctie.neemAan(notificatie);
            UUID id = notificatie.getId();

            if (status != NotificatieStatus.AANGENOMEN) {
                overgangsfunctie.voerUit(id, NotificatieStatus.IN_VERZENDING, null);
                Poging poging = new Poging(id, 1);
                pogingRepository.persist(poging);

                if (status != NotificatieStatus.IN_VERZENDING) {
                    if (notifyId != null) {
                        poging.markeerVerzonden(notifyId, clock.nu());
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

    // Zonder de wistaak die de overgangsfunctie bij elke eindstatus plant.
    private Map<UUID, Taak> perNotificatie() {
        List<Taak> taken = LeesHelper.taken();
        Map<UUID, Taak> map = new HashMap<>();

        for (Taak taak : taken) {
            if (taak.getNotificatieId() != null) {
                map.put(taak.getNotificatieId(), taak);
            }
        }

        return map;
    }
}
