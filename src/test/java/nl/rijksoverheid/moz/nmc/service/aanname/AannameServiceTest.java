package nl.rijksoverheid.moz.nmc.service.aanname;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
import nl.rijksoverheid.moz.nmc.service.BerichtType;
import nl.rijksoverheid.moz.nmc.service.Regie;
import nl.rijksoverheid.moz.nmc.service.sleutel.Sleutelbeheer;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import nl.rijksoverheid.moz.nmc.testhelper.VerstelbareClock;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
class AannameServiceTest {

    private static final String TEMPLATE_ID = BerichtType.DEMO_TEMPLATE.getTemplateId();

    // De klok staat stil, zodat de test exacte tijdstippen kan verwachten.
    private final VerstelbareClock clock = VerstelbareClock.opNu();

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
    DataSource dataSource;

    @Inject
    EventRepository eventRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @BeforeEach
    void setUp() {
        QuarkusMock.installMockForType(clock, Clock.class);
        TestDatabase.leeg();
    }

    @AfterEach
    void quotumWeg() {
        TaakFixtures.zetQuotumPerDag(null);
    }

    @Test
    void neemAan_centraal_slaatNotificatieEventEnVerzendtaakOpZonderExterneAanroep() {
        UUID id = aannameService.neemAan(centraal());

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = notificatieRepository.findById(id);
            assertEquals(NotificatieStatus.AANGENOMEN, notificatie.getStatus());
            assertEquals(0, notificatie.getVersie());
            assertEquals(NotificatieFixtures.DV_ID, notificatie.getDvId());
            assertFalse(notificatie.getAangenomenOp().isAfter(clock.nu()));
            assertEquals(new Ontvanger(Ontvanger.Soort.KVK, "12345678"),
                    sleutelbeheer.ontsleutelOntvanger(id, notificatie.getVersleuteldeGegevens()));
            assertEquals(Map.of("naam", "Voorbeeld BV"),
                    sleutelbeheer.ontsleutelPersonalisation(id, notificatie.getVersleuteldeGegevens()));

            List<Event> events = eventRepository.findByNotificatie(id);
            assertEquals(1, events.size());
            assertEquals(NotificatieStatus.AANGENOMEN, events.getFirst().getNaar());

            Taak taak = taakRepository.listAll().getFirst();
            assertEquals(TaakSoort.VERZENDEN, taak.getTaakSoort());
            assertEquals(id, taak.getNotificatieId());
            assertEquals(NotificatieFixtures.DV_ID, taak.getDvId());
            assertFalse(taak.getDue().isAfter(clock.nu()));
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
                null, null, BerichtType.DEMO_TEMPLATE, null));

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

    // Een Dienstverlener die de 202 niet zag en opnieuw aanbiedt, krijgt dezelfde notificatie terug: de
    // ontvanger krijgt het bericht niet twee keer.
    @Test
    void neemAan_zelfdeDvRefEnInhoudOpnieuw_geeftHetzelfdeIdEnMaaktNietsAan() {
        UUID eerste = aannameService.neemAan(centraal(), "order-1");

        UUID tweede = aannameService.neemAan(centraal(), "order-1");

        assertEquals(eerste, tweede);
        assertEquals(1, LeesHelper.aantalNotificaties());
        assertEquals(1, LeesHelper.aantalTaken(TaakSoort.VERZENDEN));
    }

    // De herkenning rust op referentie én inhoud: een andere ontvanger of andere berichtgegevens onder
    // dezelfde referentie is een andere notificatie, net als dezelfde inhoud onder een andere referentie.
    @Test
    void neemAan_andereInhoudOfAndereDvRef_maaktEenNieuweNotificatie() {
        UUID eerste = aannameService.neemAan(centraal(), "order-1");
        UUID andereGegevens = aannameService.neemAan(new AannameOpdracht(Regie.CENTRAAL,
                new Ontvanger(Ontvanger.Soort.KVK, "12345678"), "Gemeente Voorbeeld", "Parkeervergunning",
                BerichtType.DEMO_TEMPLATE, Map.of("naam", "Ander BV")), "order-1");
        UUID andereOntvanger = aannameService.neemAan(new AannameOpdracht(Regie.CENTRAAL,
                new Ontvanger(Ontvanger.Soort.KVK, "87654321"), "Gemeente Voorbeeld", "Parkeervergunning",
                BerichtType.DEMO_TEMPLATE, Map.of("naam", "Voorbeeld BV")), "order-1");
        UUID andereReferentie = aannameService.neemAan(centraal(), "order-2");

        assertEquals(4, Set.of(eerste, andereGegevens, andereOntvanger, andereReferentie).size());
        assertEquals(4, LeesHelper.aantalNotificaties());
    }

    // Elk veld van de aanvraag telt mee in de inhoud: verschilt er één, dan is het een andere aanvraag en
    // mag de eerdere notificatie niet terugkomen, want dan wordt deze nooit verstuurd.
    static Stream<Arguments> aanvragenMetEenAfwijkendVeld() {
        Ontvanger kvk = new Ontvanger(Ontvanger.Soort.KVK, "12345678");
        Map<String, String> gegevens = Map.of("naam", "Voorbeeld BV");

        return Stream.of(
                arguments("regie", new AannameOpdracht(Regie.DECENTRAAL, kvk, "Gemeente Voorbeeld", "Parkeervergunning",
                        BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("soort ontvanger", new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.RSIN, "12345678"),
                        "Gemeente Voorbeeld", "Parkeervergunning", BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("ontvanger", new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.KVK, "87654321"),
                        "Gemeente Voorbeeld", "Parkeervergunning", BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("dienstverlener", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Elders", "Parkeervergunning",
                        BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("dienst", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld", "Subsidie",
                        BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("geen dienst", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld", null,
                        BerichtType.DEMO_TEMPLATE, gegevens)),
                arguments("berichttype", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld", "Parkeervergunning",
                        BerichtType.STUURGROEP_AGENDA, gegevens)),
                arguments("waarde van een berichtgegeven", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld",
                        "Parkeervergunning", BerichtType.DEMO_TEMPLATE, Map.of("naam", "Ander BV"))),
                arguments("naam van een berichtgegeven", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld",
                        "Parkeervergunning", BerichtType.DEMO_TEMPLATE, Map.of("bedrijf", "Voorbeeld BV"))),
                arguments("extra berichtgegeven", new AannameOpdracht(Regie.CENTRAAL, kvk, "Gemeente Voorbeeld",
                        "Parkeervergunning", BerichtType.DEMO_TEMPLATE, Map.of("naam", "Voorbeeld BV", "datum", "1 januari"))),
                // Dezelfde tekens, anders over twee velden verdeeld.
                arguments("grens tussen dienstverlener en dienst", new AannameOpdracht(Regie.CENTRAAL, kvk,
                        "Gemeente VoorbeeldP", "arkeervergunning", BerichtType.DEMO_TEMPLATE, gegevens)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aanvragenMetEenAfwijkendVeld")
    void neemAan_zelfdeDvRefMetEenAfwijkendVeld_maaktEenNieuweNotificatie(String veld, AannameOpdracht afwijkend) {
        UUID eerste = aannameService.neemAan(centraal(), "order-1");

        UUID tweede = aannameService.neemAan(afwijkend, "order-1");

        assertNotEquals(eerste, tweede);
        assertEquals(2, LeesHelper.aantalNotificaties());
    }

    // JSON kent geen volgorde van velden: dezelfde berichtgegevens in een andere volgorde zijn dezelfde aanvraag.
    @Test
    void neemAan_berichtgegevensInEenAndereVolgorde_isDezelfdeAanvraag() {
        Map<String, String> heen = new LinkedHashMap<>();
        heen.put("naam", "Voorbeeld BV");
        heen.put("datum", "1 januari");
        heen.put("bedrag", "100");
        Map<String, String> terug = new LinkedHashMap<>();
        terug.put("bedrag", "100");
        terug.put("datum", "1 januari");
        terug.put("naam", "Voorbeeld BV");

        UUID eerste = aannameService.neemAan(metBerichtgegevens(heen), "order-1");

        assertEquals(eerste, aannameService.neemAan(metBerichtgegevens(terug), "order-1"));
    }

    private static AannameOpdracht metBerichtgegevens(Map<String, String> berichtgegevens) {
        return new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.KVK, "12345678"), "Gemeente Voorbeeld",
                "Parkeervergunning", BerichtType.DEMO_TEMPLATE, berichtgegevens);
    }

    @Test
    void neemAan_zonderDvRef_maaktElkeKeerEenNotificatie() {
        UUID eerste = aannameService.neemAan(centraal());
        UUID tweede = aannameService.neemAan(centraal(), null);

        assertNotEquals(eerste, tweede);
        assertNull(LeesHelper.notificatie(eerste).getDvRefHmac());
    }

    // Een herhaling is geen nieuwe notificatie en mag dus ook niet op het quotum stuklopen.
    @Test
    void neemAan_herhalingBijEenBereiktQuotum_geeftHetEerdereId() {
        TaakFixtures.zetQuotumPerDag(1);
        UUID eerste = aannameService.neemAan(centraal(), "order-1");

        assertEquals(eerste, aannameService.neemAan(centraal(), "order-1"));
        assertThrows(QuotumOverschredenException.class, () -> aannameService.neemAan(centraal(), "order-2"));
    }

    // Twee aanvragen met dezelfde referentie tegelijk, zoals een time-out met een directe herhaling:
    // samen één notificatie.
    @Test
    void neemAan_zelfdeDvRefTegelijk_maaktEenNotificatie() throws Exception {
        int aantal = 8;
        ExecutorService executor = Executors.newFixedThreadPool(aantal);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<UUID>> aanvragen = new ArrayList<>();
            for (int i = 0; i < aantal; i++) {
                aanvragen.add(executor.submit(() -> {
                    start.await();

                    return aannameService.neemAan(centraal(), "order-tegelijk");
                }));
            }
            start.countDown();

            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> aanvraag : aanvragen) {
                ids.add(aanvraag.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, ids.size());
            assertEquals(1, LeesHelper.aantalNotificaties());
        } finally {
            executor.shutdownNow();
        }
    }

    // De Dienstverlener kiest de referentie zelf, dus er kan een naam of nummer in staan. Op de rij staat
    // alleen een HMAC van 32 bytes; de referentie zelf is in geen enkele kolom terug te vinden.
    @Test
    void neemAan_metDvRef_bewaartDeReferentieNietLeesbaar() throws Exception {
        String dvRef = "order-van-jan-jansen-2030";

        UUID id = aannameService.neemAan(centraal(), dvRef);

        Map<String, byte[]> rij = NotificatieFixtures.leesRuweRij(dataSource, id);
        assertEquals(32, rij.get("dv_ref_hmac").length);
        assertEquals(32, rij.get("payload_hash").length);
        assertEquals("1", new String(rij.get("pepper_versie"), StandardCharsets.UTF_8));
        rij.forEach((kolom, waarde) -> assertFalse(waarde != null
                        && new String(waarde, StandardCharsets.ISO_8859_1).contains(dvRef),
                "kolom " + kolom + " bevat de referentie"));
    }

    // De herkenning geldt zolang de rij er is, ook nadat de sleutel van de notificatie gewist is.
    @Test
    void neemAan_herhalingNaHetWissenVanDeSleutel_geeftHetEerdereId() {
        UUID eerste = aannameService.neemAan(centraal(), "order-1");
        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.wisSleutel(eerste));

        assertEquals(eerste, aannameService.neemAan(centraal(), "order-1"));
    }

    // Het quotum telt per dienstverlener per dag; zonder quotum in het register is er geen grens.
    @Test
    void neemAan_quotumBereikt_weigertEnLegtNietsVast() {
        TaakFixtures.zetQuotumPerDag(1);
        aannameService.neemAan(centraal());

        assertThrows(QuotumOverschredenException.class, () -> aannameService.neemAan(centraal()));

        assertEquals(1, LeesHelper.aantalNotificaties());
        assertEquals(1, LeesHelper.aantalTaken());
    }

    // Het quotum telt per kalenderdag in UTC: wat voor middernacht is aangenomen telt de dag erna niet mee,
    // wat op middernacht is aangenomen wel.
    @Test
    void neemAan_quotumBereikt_teltDeVorigeDagNietMee() {
        TaakFixtures.zetQuotumPerDag(1);
        clock.zet(Instant.parse("2030-01-01T23:59:59Z"));
        aannameService.neemAan(centraal());

        clock.zet(Instant.parse("2030-01-02T00:00:00Z"));
        aannameService.neemAan(centraal());

        assertThrows(QuotumOverschredenException.class, () -> aannameService.neemAan(centraal()));
        assertEquals(2, LeesHelper.aantalNotificaties());
    }

    @Test
    void neemAan_zonderQuotum_kentGeenGrens() {
        for (int i = 0; i < 3; i++) {
            aannameService.neemAan(centraal());
        }

        assertEquals(3, LeesHelper.aantalNotificaties());
    }

    @Test
    void aannameOpdracht_zonderOntvanger_weigert() {
        assertThrows(NullPointerException.class,
                () -> new AannameOpdracht(Regie.DECENTRAAL, null, null, null, BerichtType.DEMO_TEMPLATE, null));
    }

    private static AannameOpdracht centraal() {
        return new AannameOpdracht(Regie.CENTRAAL, new Ontvanger(Ontvanger.Soort.KVK, "12345678"),
                "Gemeente Voorbeeld", "Parkeervergunning", BerichtType.DEMO_TEMPLATE, Map.of("naam", "Voorbeeld BV"));
    }
}
