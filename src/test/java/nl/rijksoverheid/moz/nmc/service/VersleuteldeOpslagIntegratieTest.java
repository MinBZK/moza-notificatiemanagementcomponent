package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.model.SendEmailResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.api.ProfielApi;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.ContactgegevenResponse;
import nl.rijksoverheid.moz.nmc.client.profielservice.generated.model.PartijResponse;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.domain.VersleuteldeGegevens;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Leest de notificatierij na een echte verzending buiten Hibernate om, zoals een {@code SELECT} van
 * een beheerder dat zou doen, en controleert dat geen kolom het e-mailadres of de personalisation
 * leesbaar bevat.
 */
@QuarkusTest
class VersleuteldeOpslagIntegratieTest {

    private static final String EMAIL = "geheim.adres@example.nl";
    private static final Map<String, String> PERSONALISATION = Map.of(
            "naam", "Vertrouwelijke Holding BV",
            "zaaknummer", "ZAAK-2026-000123");
    private static final String CALLBACK_URL = "https://aanroeper.example.nl/status";
    private static final String KVK_NUMMER = "90004321";

    @InjectMock
    @RestClient
    ProfielApi profielApi;

    @InjectMock
    @RestClient
    SendAMessageApi sendAMessageApi;

    @InjectMock
    NotifyNLJwtFactory notifyNLJwtFactory;

    @Inject
    DataSource dataSource;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    Sleutelbeheer sleutelbeheer;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
        Mockito.when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
        Mockito.when(sendAMessageApi.sendEmail(any()))
                .thenReturn(new SendEmailResponse().id(UUID.randomUUID().toString()));
    }

    @Test
    void decentraleNotificatieVersturen_rijBevatGeenLeesbaarAdresOfPersonalisation() throws Exception {
        UUID id = verstuur();

        Map<String, byte[]> rij = leesRij(id);

        assertNotNull(rij.get("ontvanger_versleuteld"));
        assertNotNull(rij.get("personalisation_versleuteld"));
        assertNotNull(rij.get("sleutel_gewrapt"));
        assertEquals("1", new String(rij.get("kek_versie"), StandardCharsets.UTF_8));
        // Controle op de zoekmethode zelf: de callback-URL staat wél leesbaar op de rij.
        assertTrue(bevat(rij.get("callback_url"), CALLBACK_URL.getBytes(StandardCharsets.UTF_8)));

        List<String> geheimen = new ArrayList<>(List.of(EMAIL));
        geheimen.addAll(PERSONALISATION.values());

        for (Map.Entry<String, byte[]> kolom : rij.entrySet()) {
            for (String geheim : geheimen) {
                assertFalse(bevat(kolom.getValue(), geheim.getBytes(StandardCharsets.UTF_8)),
                        "kolom " + kolom.getKey() + " bevat " + geheim + " leesbaar");
            }
        }
    }

    @Test
    void decentraleNotificatieVersturen_rijIsNaHerladenTeOntsleutelen() {
        UUID id = verstuur();

        VersleuteldeGegevens gegevens = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = notificatieRepository.findById(id);

            return notificatie.getVersleuteldeGegevens();
        });

        assertEquals(Ontvanger.email(EMAIL), sleutelbeheer.ontsleutelOntvanger(id, gegevens));
        assertEquals(PERSONALISATION, sleutelbeheer.ontsleutelPersonalisation(id, gegevens));
    }

    // Centrale regie: het adres uit de Profielservice staat nergens op de rij, ook niet versleuteld; het
    // identificerend nummer staat er alleen versleuteld.
    @Test
    void centraleNotificatieVersturen_rijBevatGeenAdresEnGeenLeesbaarNummer() throws Exception {
        Mockito.when(profielApi.apiProfielserviceV1PartijPost(any())).thenReturn(new PartijResponse()
                .partijId(UUID.randomUUID())
                .contactgegevens(List.of(new ContactgegevenResponse()
                        .type(ContactgegevenResponse.TypeEnum.EMAIL).waarde(EMAIL).isDefault(true))));

        String notificatieId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "identificatieType", "KVK",
                        "identificatieNummer", KVK_NUMMER,
                        "dienstverlener", "Gemeente Voorbeeld",
                        "berichtType", "Stuurgroep Agenda",
                        "berichtgegevens", PERSONALISATION))
                .when().post("/api/nmc/v1/centraal/notificaties")
                .then()
                .statusCode(200)
                .extract().path("notificatieId");
        UUID id = UUID.fromString(notificatieId);

        for (Map.Entry<String, byte[]> kolom : leesRij(id).entrySet()) {
            for (String geheim : List.of(EMAIL, KVK_NUMMER)) {
                assertFalse(bevat(kolom.getValue(), geheim.getBytes(StandardCharsets.UTF_8)),
                        "kolom " + kolom.getKey() + " bevat " + geheim + " leesbaar");
            }
        }

        VersleuteldeGegevens gegevens = QuarkusTransaction.requiringNew().call(() ->
                notificatieRepository.findById(id).getVersleuteldeGegevens());
        assertEquals(new Ontvanger(Ontvanger.Soort.KVK, KVK_NUMMER), sleutelbeheer.ontsleutelOntvanger(id, gegevens));
    }

    private UUID verstuur() {
        String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "emailAdres", EMAIL,
                        "berichtType", "Stuurgroep Agenda",
                        "berichtgegevens", PERSONALISATION,
                        "callbackUrl", CALLBACK_URL))
                .when().post("/api/nmc/v1/decentraal/notificaties")
                .then()
                .statusCode(200)
                .extract().path("notificatieId");

        return UUID.fromString(id);
    }

    // Elke kolom als bytes: bytea zoals opgeslagen, andere typen in hun tekstvorm.
    private Map<String, byte[]> leesRij(UUID id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM notificatie WHERE id = ?")) {
            statement.setObject(1, id);

            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "notificatie " + id + " niet gevonden");
                ResultSetMetaData metaData = resultSet.getMetaData();
                Map<String, byte[]> rij = new LinkedHashMap<>();

                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    Object waarde = resultSet.getObject(i);
                    byte[] bytes = waarde instanceof byte[] b ? b
                            : waarde == null ? null : String.valueOf(waarde).getBytes(StandardCharsets.UTF_8);
                    rij.put(metaData.getColumnName(i), bytes);
                }

                return rij;
            }
        }
    }

    private static boolean bevat(byte[] hooiberg, byte[] naald) {
        if (hooiberg == null) {
            return false;
        }

        for (int start = 0; start <= hooiberg.length - naald.length; start++) {
            int i = 0;

            while (i < naald.length && hooiberg[start + i] == naald[i]) {
                i++;
            }

            if (i == naald.length) {
                return true;
            }
        }

        return false;
    }
}
