package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekContext;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
class ContactherstelControllerTest {

    private static final String PAD = "/api/nmc/v1/contactherstellen";
    private static final String PAD_MET_ID = PAD + "/{contactherstelId}";

    @InjectSpy
    LogboekContext logboekContext;

    @Inject
    HashHelper hashHelper;

    @Inject
    DvProvider dvProvider;

    @Test
    void contactherstelStarten_geldigeAanvraag_geeft501() {
        given()
                .contentType(ContentType.JSON)
                .body(geldigeStartAanvraag())
                .when().post(PAD)
                .then()
                .statusCode(501)
                .contentType("application/problem+json")
                .body("status", equalTo(501))
                .body("title", equalTo("Niet geïmplementeerd"))
                .body("detail", equalTo("Contactherstel starten is nog niet geïmplementeerd"));
    }

    @Test
    void contactherstelStarten_geldigeAanvraag_zetGehashteOntvangerAlsBetrokkene() {
        given()
                .contentType(ContentType.JSON)
                .body(geldigeStartAanvraag())
                .when().post(PAD)
                .then()
                .statusCode(501);

        verify(logboekContext).setDataSubjectId(hashHelper.hashIdentifier("12345678"));
        verify(logboekContext, never()).setDataSubjectId("12345678");
        verify(logboekContext).setDataSubjectType("KVK");
    }

    @Test
    void contactherstelStarten_ongeldigeAanvraag_zetGeenBetrokkene() {
        Map<String, Object> aanvraag = geldigeStartAanvraag();
        aanvraag.put("emailAdres", "geen-adres");

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post(PAD)
                .then()
                .statusCode(400);

        verify(logboekContext, never()).setDataSubjectId(anyString());
    }

    @Test
    void contactherstelStarten_ontbrekendVerplichtVeld_geeft400() {
        Map<String, Object> aanvraag = geldigeStartAanvraag();
        aanvraag.remove("notificatieId");

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void contactherstelStarten_ongeldigEmailAdres_geeft400MetViolation() {
        Map<String, Object> aanvraag = geldigeStartAanvraag();
        aanvraag.put("emailAdres", "geen-adres");

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post(PAD)
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("violations.field", hasItem("emailAdres"));
    }

    @Test
    void contactherstelStarten_notificatieStatusBuitenDeEnum_geeft400() {
        Map<String, Object> aanvraag = geldigeStartAanvraag();
        aanvraag.put("notificatieStatus", "bezorgd");

        given()
                .contentType(ContentType.JSON)
                .body(aanvraag)
                .when().post(PAD)
                .then()
                .statusCode(400);
    }

    @Test
    void contactherstelOpvragen_geeft501() {
        given()
                .when().get(PAD_MET_ID, UUID.randomUUID())
                .then()
                .statusCode(501)
                .contentType("application/problem+json")
                .body("detail", equalTo("Contactherstel opvragen is nog niet geïmplementeerd"));

        verify(logboekContext).setDataSubjectId(dvProvider.huidigeDvId().toString());
        verify(logboekContext).setDataSubjectType("DIENSTVERLENER");
    }

    @Test
    void contactherstelBijwerken_afgerond_geeft501() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("status", "afgerond"))
                .when().patch(PAD_MET_ID, UUID.randomUUID())
                .then()
                .statusCode(501)
                .contentType("application/problem+json")
                .body("detail", equalTo("Contactherstel afronden of intrekken is nog niet geïmplementeerd"));

        verify(logboekContext).setDataSubjectId(dvProvider.huidigeDvId().toString());
        verify(logboekContext).setDataSubjectType("DIENSTVERLENER");
    }

    @Test
    void contactherstelBijwerken_statusBuitenDeEnum_geeft400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("status", "gestart"))
                .when().patch(PAD_MET_ID, UUID.randomUUID())
                .then()
                .statusCode(400);
    }

    @Test
    void contactherstelBijwerken_zonderStatus_geeft400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when().patch(PAD_MET_ID, UUID.randomUUID())
                .then()
                .statusCode(400);
    }

    private static Map<String, Object> geldigeStartAanvraag() {
        Map<String, Object> aanvraag = new HashMap<>();
        aanvraag.put("identificatieType", "KVK");
        aanvraag.put("identificatieNummer", "12345678");
        aanvraag.put("emailAdres", "ontvanger@example.nl");
        aanvraag.put("notificatieId", UUID.randomUUID().toString());
        aanvraag.put("berichtsoort", "kennisgeving");
        aanvraag.put("notificatieStatus", "niet-bezorgbaar");

        return aanvraag;
    }
}
