package nl.rijksoverheid.moz.nmc.service.onderhoud;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import nl.rijksoverheid.moz.nmc.job.TaakRonde;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.service.Overgangsfunctie;
import nl.rijksoverheid.moz.nmc.service.sleutel.Sleutelbeheer;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.SleutelFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static nl.rijksoverheid.moz.nmc.testhelper.LeesHelper.notificatie;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// %test: bewaartermijn afleverbewijs 365 dagen, maximale cursorleeftijd 30 dagen, batch 2, hoogstens
// 3 batches per stap, partities van 50 transactie-ids, huidige KEK-versie 2, cluster-epoch 1.
@QuarkusTest
class OnderhoudTaakHandlerTest {

    // base64 van "test-kek-niet-voor-productie-001": versie 1 uit de testconfiguratie.
    private static final String TEST_KEK_1 = "dGVzdC1rZWstbmlldC12b29yLXByb2R1Y3RpZS0wMDE=";
    private static final Ontvanger ONTVANGER = Ontvanger.email("burger@example.nl");

    @Inject
    OnderhoudTaakHandler onderhoudTaakHandler;

    @Inject
    TaakWorker taakWorker;

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    PogingRepository pogingRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @AfterEach
    void herstelRegister() {
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.zetMaxCursorleeftijd(entityManager,
                NotificatieFixtures.DV_ID, null));
    }

    // Zeven verlopen notificaties bij batches van twee en hoogstens drie batches: zes in de eerste
    // ronde, de zevende in de volgende. Een recente met en een zonder eindstatus blijven staan.
    @Test
    void onderhoud_naDeBewaartermijn_verwijdertNotificatiesEnPogingenInBatches() {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        NotificatieFixtures.plantNotificaties(entityManager, 7, nu.minusDays(400), NotificatieStatus.NIET_BEZORGBAAR, 2);
        UUID recent = UUID.randomUUID();
        UUID lopend = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.voegNotificatieToe(entityManager, recent, NotificatieStatus.NIET_BEZORGBAAR, nu.minusDays(364));
            NotificatieFixtures.voegNotificatieToe(entityManager, lopend, NotificatieStatus.VERZONDEN, null);
            NotificatieFixtures.voegPogingToe(entityManager, lopend, 1, UUID.randomUUID(),
                    PogingStatus.VERZONDEN, nu.minusDays(400));
        });

        onderhoud();

        assertEquals(3, aantal(notificatieRepository::count));
        assertEquals(3, aantal(pogingRepository::count), "twee van de laatste verlopen en die van de lopende");

        onderhoud();

        assertEquals(2, aantal(notificatieRepository::count));
        assertEquals(1, aantal(pogingRepository::count));
        assertNotNull(LeesHelper.notificatie(recent));
        assertNotNull(LeesHelper.notificatie(lopend));
    }

    // Een KEK-rotatie wrapt alleen de sleutel opnieuw: de versleutelde gegevens blijven byte voor byte
    // gelijk en gaan open onder de nieuwe versie. Een sleutel die niet onder zijn versie open gaat,
    // blijft staan zonder de andere tegen te houden.
    @Test
    void onderhoud_sleutelsOnderOudereKekVersie_herwraptZeZonderHerversleuteling() {
        Sleutelbeheer versie1 = SleutelFixtures.sleutelbeheer(1, Map.of(1, TEST_KEK_1));
        List<UUID> ids = List.of(opgeslagen(versie1), opgeslagen(versie1), opgeslagen(versie1));
        UUID kapot = opgeslagen(SleutelFixtures.sleutelbeheer(1, Map.of(1, SleutelFixtures.KEK_1)));
        Map<UUID, VersleuteldeGegevens> voor = ids.stream()
                .collect(Collectors.toMap(id -> id, id -> notificatie(id).getVersleuteldeGegevens()));

        onderhoud();

        for (UUID id : ids) {
            VersleuteldeGegevens na = notificatie(id).getVersleuteldeGegevens();
            assertEquals(2, na.kekVersie());
            assertArrayEquals(voor.get(id).ontvangerVersleuteld(), na.ontvangerVersleuteld());
            assertArrayEquals(voor.get(id).personalisationVersleuteld(), na.personalisationVersleuteld());
            assertFalse(Arrays.equals(voor.get(id).sleutelGewrapt(), na.sleutelGewrapt()));
            assertEquals(ONTVANGER, sleutelbeheer.ontsleutelOntvanger(id, na));
            assertEquals(Map.of("naam", "Voorbeeld BV"), sleutelbeheer.ontsleutelPersonalisation(id, na));
        }

        assertEquals(1, notificatie(kapot).getVersleuteldeGegevens().kekVersie());
    }

    @Test
    void onderhoud_viaDeWorker_plantZichzelfOpnieuw() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(TaakSoort.ONDERHOUD, null, null, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });

        assertEquals(1, TaakRonde.draai(taakWorker, TaakSoort.ONDERHOUD));

        Taak taak = LeesHelper.taak(TaakSoort.ONDERHOUD, id).orElseThrow();
        assertTrue(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(59)));
        assertEquals(0, taak.getPogingen());
    }

    // De tokens van een verstreken tijdvak zijn vervallen; de rij blijft een uur staan en gaat dan weg.
    @Test
    void onderhoud_tijdvakkenVanHetVerzendbudgetOuderDanEenUur_verwijdertAlleenDie() {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        OffsetDateTime oud = nu.minusMinutes(61);
        OffsetDateTime recent = nu.minusMinutes(59);
        QuarkusTransaction.requiringNew().run(() -> {
            TaakFixtures.voegBudgetrijToe(entityManager, oud, 20);
            TaakFixtures.voegBudgetrijToe(entityManager, recent, 20);
        });

        onderhoud();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, TaakFixtures.telBudgetrijen(entityManager));
            assertEquals(20, TaakFixtures.tokens(entityManager, recent));
        });
    }

    // Elke stap kan lang duren; de handler verlengt de lease na het aanmaken van een partitie, na het
    // verwijderen van notificaties en na het verwijderen van partities, ook als er niets te doen was.
    @Test
    void onderhoud_zonderWerk_verlengtDeLeaseTussenDeStappen() {
        AtomicInteger verlengd = new AtomicInteger();

        onderhoudTaakHandler.voerUit(onderhoudstaak(), verlengd::incrementAndGet);

        assertEquals(3, verlengd.get());
    }

    private void onderhoud() {
        onderhoudTaakHandler.voerUit(onderhoudstaak(), () -> {
        });
    }

    private static Taak onderhoudstaak() {
        return new Taak(TaakSoort.ONDERHOUD, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null);
    }

    private UUID opgeslagen(Sleutelbeheer beheer) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, OffsetDateTime.now());
            notificatie.bewaarVersleuteldeGegevens(beheer.versleutel(notificatie.getId(), ONTVANGER,
                    Map.of("naam", "Voorbeeld BV")));
            overgangsfunctie.neemAan(notificatie);

            return notificatie.getId();
        });
    }

    private static long aantal(Supplier<Long> teller) {
        return QuarkusTransaction.requiringNew().call(teller::get);
    }
}
