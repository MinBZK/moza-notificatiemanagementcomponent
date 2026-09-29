package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.domain.TaakStatus;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie;
import nl.rijksoverheid.moz.nmc.job.TaakWorker;
import nl.rijksoverheid.moz.nmc.repository.EventRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.repository.WebhookpositieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LogVanger;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.WebhookOntvanger;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// %test: nmc.webhook.bundel=3, nmc.webhook.max-mislukkingen=2, herpoging 30s, pauze 15m, feed max-pagina 10.
@QuarkusTest
class TerugkoppelTaakHandlerTest {

    private static final String FEED = "/api/nmc/v1/notificaties/wijzigingen";

    @TestHTTPResource("/test/webhook")
    URL ontvanger;

    @InjectMock
    Aanroeplimiet aanroeplimiet;

    @Inject
    TaakWorker taakWorker;

    @Inject
    TaakClaimer taakClaimer;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EventRepository eventRepository;

    @Inject
    WebhookpositieRepository webhookpositieRepository;

    @Inject
    EntityManager entityManager;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        when(aanroeplimiet.registreer(any())).thenReturn(true);
        WebhookOntvanger.AANROEPEN.clear();
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            eventRepository.deleteAll();
            entityManager.createNativeQuery("DELETE FROM webhookpositie").executeUpdate();
            taakRepository.persist(new Taak(TaakSoort.TERUGKOPPELEN, NotificatieFixtures.DV_ID, null,
                    OffsetDateTime.now(ZoneOffset.UTC), null, null));
        });
    }

    @AfterEach
    void tearDown() {
        zetWebhook(null, null);
    }

    @Test
    void terugkoppelen_levertDezelfdeEventsAlsDeFeed_inDezelfdeVormEnVolgorde() throws Exception {
        schrijfEvents(5);
        zetWebhook(ontvanger + "/204", null);

        assertEquals(1, taakWorker.verwerk(TaakSoort.TERUGKOPPELEN));
        assertEquals(1, taakWorker.verwerk(TaakSoort.TERUGKOPPELEN));
        assertEquals(1, taakWorker.verwerk(TaakSoort.TERUGKOPPELEN));

        assertEquals(2, WebhookOntvanger.AANROEPEN.size(), "twee bundels van ten hoogste drie; de derde ronde vond niets");
        JsonNode feed = objectMapper.readTree(given().when().get(FEED).then().statusCode(200).extract().asString());
        assertEquals(feed.get("events"), geleverdeEvents(WebhookOntvanger.AANROEPEN));
        assertEquals(feed.get("cursor").asText(), WebhookOntvanger.AANROEPEN.getLast().cursor());
        assertEquals(Cursor.decodeer(feed.get("cursor").asText()), positie().positie());

        Taak taak = terugkoppeltaak();
        assertEquals(TaakStatus.OPEN, taak.getStatus());
        assertEquals(0, taak.getPogingen());
        assertTrue(taak.getDue().isAfter(OffsetDateTime.now(ZoneOffset.UTC)), "na een lege ronde op het interval");
    }

    // De taak plant zichzelf zijn hele levensduur opnieuw; fouten die verspreid over die tijd vallen
    // mogen niet optellen tot mislukt.
    @Test
    void geslaagdeRonde_zetDePogingenTerug() {
        zetWebhook(ontvanger + "/204", null);
        zetPogingen(1);
        schrijfEvents(1);

        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertEquals(0, terugkoppeltaak().getPogingen(), "na een levering");

        zetPogingen(1);
        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertEquals(0, terugkoppeltaak().getPogingen(), "na een ronde zonder events");
        assertEquals(TaakStatus.OPEN, terugkoppeltaak().getStatus());
    }

    // De JWT moet te controleren zijn met alleen wat het NMC publiceert.
    @Test
    void terugkoppelen_jwtIsTeVerifierenMetDeJwks() throws Exception {
        schrijfEvents(1);
        String url = ontvanger + "/204";
        zetWebhook(url, null);

        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        String autorisatie = WebhookOntvanger.AANROEPEN.getFirst().autorisatie();
        assertTrue(autorisatie.startsWith("Bearer "));
        JsonWebKeySet jwks = new JsonWebKeySet(given().when().get("/api/nmc/v1/.well-known/jwks.json")
                .then().statusCode(200).extract().asString());
        JwtClaims claims = new JwtConsumerBuilder()
                .setVerificationKeyResolver(new JwksVerificationKeyResolver(jwks.getJsonWebKeys()))
                .setExpectedIssuer("https://mijnoverheidzakelijk.nl/nmc")
                .setExpectedAudience(url)
                .setRequireExpirationTime()
                .setRequireIssuedAt()
                .build()
                .processToClaims(autorisatie.substring("Bearer ".length()));
        assertEquals(Duration.ofMinutes(5).toSeconds(),
                claims.getExpirationTime().getValue() - claims.getIssuedAt().getValue());
    }

    @Test
    void mislukteLevering_hervatVanafDeLeverpositie_zonderOverslaanOfDubbel() throws Exception {
        schrijfEvents(5);
        zetWebhook(ontvanger + "/204", null);
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        Cursor naEersteBundel = positie().positie();

        zetWebhook(ontvanger + "/500", null);
        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        assertEquals(naEersteBundel, positie().positie(), "een mislukte levering verzet de positie niet");
        assertEquals(1, positie().mislukkingen());
        Taak taak = terugkoppeltaak();
        assertEquals(0, taak.getPogingen(), "een mislukte levering kost de taak geen poging");
        assertBinnen(Duration.ofSeconds(30), taak.getDue());

        zetWebhook(ontvanger + "/204", null);
        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        List<WebhookOntvanger.Aanroep> aanroepen = WebhookOntvanger.AANROEPEN;
        assertEquals(3, aanroepen.size());
        assertEquals(aanroepen.get(1).body(), aanroepen.get(2).body(), "dezelfde bundel opnieuw, vanaf dezelfde positie");
        JsonNode feed = objectMapper.readTree(given().when().get(FEED).then().statusCode(200).extract().asString());
        assertEquals(feed.get("events"), geleverdeEvents(List.of(aanroepen.get(0), aanroepen.get(2))));
        assertEquals(0, positie().mislukkingen());
    }

    // Default twee mislukkingen (onder %test); het register zegt drie en gaat voor.
    @Test
    void herhaaldFalen_pauzeertNaHetAfgesprokenAantal_metOplopendeWachttijd_enHervat() {
        schrijfEvents(1);
        zetWebhook(ontvanger + "/503", 3);

        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertNull(positie().gepauzeerdTot());
        assertBinnen(Duration.ofSeconds(30), terugkoppeltaak().getDue());

        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertNull(positie().gepauzeerdTot());
        assertBinnen(Duration.ofMinutes(1), terugkoppeltaak().getDue());

        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertNotNull(positie().gepauzeerdTot(), "na drie mislukkingen gepauzeerd");
        assertBinnen(Duration.ofMinutes(15), terugkoppeltaak().getDue());

        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
        assertBinnen(Duration.ofMinutes(30), terugkoppeltaak().getDue());
        assertEquals(terugkoppeltaak().getDue(), positie().gepauzeerdTot());

        zetWebhook(ontvanger + "/204", 3);
        maakAanDeBeurt();
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        Webhookpositie hervat = positie();
        assertNull(hervat.gepauzeerdTot());
        assertEquals(0, hervat.mislukkingen());
        assertNotNull(hervat.positie());
        assertEquals(5, WebhookOntvanger.AANROEPEN.size());
        assertEquals(0, terugkoppeltaak().getPogingen());
    }

    @Test
    void wachttijd_verdubbeltEnIsBegrensdOpEenDag() {
        assertEquals(Duration.ofMinutes(15), TerugkoppelTaakHandler.oplopend(Duration.ofMinutes(15), 0));
        assertEquals(Duration.ofMinutes(60), TerugkoppelTaakHandler.oplopend(Duration.ofMinutes(15), 2));
        assertEquals(Duration.ofDays(1), TerugkoppelTaakHandler.oplopend(Duration.ofMinutes(15), 7));
        assertEquals(Duration.ofDays(1), TerugkoppelTaakHandler.oplopend(Duration.ofMinutes(15), 5000));
    }

    @Test
    void ongeldigeWebhookUrl_pauzeertZonderAanroepEnLogtError() {
        schrijfEvents(1);
        zetWebhook("https://intern.local/webhook", null);

        List<String> fouten;
        try (LogVanger vanger = LogVanger.van(TerugkoppelTaakHandler.class)) {
            taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);
            fouten = vanger.regelsOpNiveau(Level.SEVERE);
        }

        assertEquals(0, WebhookOntvanger.AANROEPEN.size());
        assertEquals(1, fouten.size());
        assertTrue(fouten.getFirst().contains("interne hostnaam"));
        assertNotNull(positie().gepauzeerdTot());
        assertBinnen(Duration.ofMinutes(15), terugkoppeltaak().getDue());
        assertEquals(0, terugkoppeltaak().getPogingen());
    }

    @Test
    void gepauzeerdeWebhook_dieToevalligAanDeBeurtIs_wachtTotHetEindVanDePauze() {
        schrijfEvents(1);
        zetWebhook(ontvanger + "/204", null);
        OffsetDateTime tot = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).withNano(0);
        QuarkusTransaction.requiringNew().run(() -> webhookpositieRepository.bewaar(
                new Webhookpositie(NotificatieFixtures.DV_ID, null, 4, tot), OffsetDateTime.now(ZoneOffset.UTC)));

        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        assertEquals(0, WebhookOntvanger.AANROEPEN.size());
        assertEquals(tot.toInstant(), terugkoppeltaak().getDue().toInstant());
    }

    @Test
    void geenWebhookMeer_rondtDeTaakAf() {
        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.TERUGKOPPELEN)));
    }

    @Test
    void taakZonderDienstverlener_vervalt() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            taakRepository.persist(new Taak(TaakSoort.TERUGKOPPELEN, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null));
        });

        taakWorker.verwerk(TaakSoort.TERUGKOPPELEN);

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> taakRepository.count("soort", TaakSoort.TERUGKOPPELEN)));
    }

    // Eén taak per dienstverlener, geclaimd met SKIP LOCKED en een lease: twee workers die tegelijk
    // claimen krijgen hem samen hooguit één keer.
    @Test
    void tweeWorkersTegelijk_claimenDeTerugkoppeltaakHooguitEenKeer() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<List<Taak>>> claims = new ArrayList<>();

            for (int i = 0; i < 2; i++) {
                claims.add(workers.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);

                    return taakClaimer.claim(TaakSoort.TERUGKOPPELEN, 10);
                }));
            }

            start.countDown();
            int geclaimd = 0;

            for (Future<List<Taak>> claim : claims) {
                geclaimd += claim.get(20, TimeUnit.SECONDS).size();
            }

            assertEquals(1, geclaimd);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void tweedeTerugkoppeltaakVoorDezelfdeDienstverlener_wordtGeweigerd() {
        assertThrows(PersistenceException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.persist(new Taak(TaakSoort.TERUGKOPPELEN, NotificatieFixtures.DV_ID, null,
                    OffsetDateTime.now(ZoneOffset.UTC), null, null));
            taakRepository.flush();
        }));
    }

    private ArrayNode geleverdeEvents(List<WebhookOntvanger.Aanroep> aanroepen) throws Exception {
        ArrayNode alle = objectMapper.createArrayNode();

        for (WebhookOntvanger.Aanroep aanroep : aanroepen) {
            alle.addAll((ArrayNode) objectMapper.readTree(aanroep.body()));
        }

        return alle;
    }

    private void schrijfEvents(int aantal) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < aantal; i++) {
                entityManager.createNativeQuery("INSERT INTO event (tijdstip, dv_id, notificatie_id, volgnummer, naar) "
                                + "VALUES (now(), ?1, ?2, 0, 'AANGENOMEN')")
                        .setParameter(1, NotificatieFixtures.DV_ID)
                        .setParameter(2, UUID.randomUUID())
                        .executeUpdate();
            }
        });
    }

    private void zetWebhook(String url, Integer maxMislukkingen) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE dienstverlener SET webhook_url = CAST(?1 AS varchar), "
                        + "webhook_max_mislukkingen = CAST(?2 AS integer) WHERE id = ?3")
                .setParameter(1, url)
                .setParameter(2, maxMislukkingen)
                .setParameter(3, NotificatieFixtures.DV_ID)
                .executeUpdate());
    }

    private void zetPogingen(int pogingen) {
        QuarkusTransaction.requiringNew().run(() -> entityManager
                .createNativeQuery("UPDATE taak SET pogingen = ?1 WHERE soort = 'TERUGKOPPELEN'")
                .setParameter(1, pogingen)
                .executeUpdate());
    }

    // Laat de wachttijd en een eventuele pauze verstrijken.
    private void maakAanDeBeurt() {
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery("UPDATE taak SET due = now() - interval '1 second' WHERE soort = 'TERUGKOPPELEN'")
                    .executeUpdate();
            entityManager.createNativeQuery("UPDATE webhookpositie SET gepauzeerd_tot = now() - interval '1 second' "
                    + "WHERE gepauzeerd_tot IS NOT NULL").executeUpdate();
        });
    }

    private Taak terugkoppeltaak() {
        return QuarkusTransaction.requiringNew().call(() -> taakRepository.find("soort", TaakSoort.TERUGKOPPELEN).singleResult());
    }

    private Webhookpositie positie() {
        return QuarkusTransaction.requiringNew().call(() -> webhookpositieRepository.zoek(NotificatieFixtures.DV_ID));
    }

    // Het uitstel ligt op nu plus de wachttijd, met ruimte voor de looptijd van de test.
    private static void assertBinnen(Duration wachttijd, OffsetDateTime due) {
        OffsetDateTime verwacht = OffsetDateTime.now(ZoneOffset.UTC).plus(wachttijd);

        assertTrue(due.isAfter(verwacht.minusSeconds(20)) && due.isBefore(verwacht.plusSeconds(5)),
                "due " + due + " hoort rond " + verwacht + " te liggen");
    }
}
