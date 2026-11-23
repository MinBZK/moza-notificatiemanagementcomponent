package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NotifyNLFoutmeldingTest {

    @Test
    void beschrijf_foutformaatVanNotifyNL_noemtErrorEnMessage() {
        String body = "{\"errors\":[{\"error\":\"BadRequestError\",\"message\":\"Missing personalisation: naam\"},"
                + "{\"error\":\"ValidationError\",\"message\":\"template_id is not a valid UUID\"}],\"status_code\":400}";

        assertEquals("BadRequestError: Missing personalisation: naam; ValidationError: template_id is not a valid UUID",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), body));
    }

    @Test
    void beschrijf_meerDanVijfFouten_noemtAlleenDeEersteVijf() {
        StringBuilder fouten = new StringBuilder();

        for (int i = 1; i <= 6; i++) {
            fouten.append(i == 1 ? "" : ",").append("{\"error\":\"E").append(i).append("\",\"message\":\"m\"}");
        }

        assertEquals("E1: m; E2: m; E3: m; E4: m; E5: m",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), "{\"errors\":[" + fouten + "]}"));
    }

    // Een regeleinde in de melding zou een eigen logregel kunnen vervalsen; een lange melding wordt afgekapt.
    @Test
    void beschrijf_stuurtekensEnLangeMelding_wordenOpgeschoond() {
        String lang = "x".repeat(NotifyNLFoutmelding.MAX_LENGTE + 1);
        String body = "{\"errors\":[{\"error\":\"Fout\\nNEP\",\"message\":\"" + lang + "\"}]}";

        assertEquals("Fout NEP: " + "x".repeat(NotifyNLFoutmelding.MAX_LENGTE) + "…",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), body));
    }

    @Test
    void beschrijf_meldingVanPreciesDeMaximaleLengte_blijftHeel() {
        String precies = "x".repeat(NotifyNLFoutmelding.MAX_LENGTE);

        assertEquals("Fout: " + precies, NotifyNLFoutmelding.beschrijf(antwoord("application/json"),
                "{\"errors\":[{\"error\":\"Fout\",\"message\":\"" + precies + "\"}]}"));
    }

    @Test
    void beschrijf_kortePlatteTekst_gaatMeeAlsMeldingVanEenTussenliggendeServer() {
        assertEquals("melding van een tussenliggende server: \"Bad Gateway\"",
                NotifyNLFoutmelding.beschrijf(antwoord("text/plain; charset=utf-8"), "Bad Gateway"));
        assertEquals("melding van een tussenliggende server: \"" + "x".repeat(100) + "\"",
                NotifyNLFoutmelding.beschrijf(antwoord("text/plain"), "x".repeat(100)));
    }

    @Test
    void beschrijf_langePlatteTekst_noemtAlleenTypeOmvangEnServer() {
        assertEquals("geen foutformaat van NotifyNL (type text/plain, 101 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("text/plain"), "x".repeat(101)));
    }

    @Test
    void beschrijf_andereBody_noemtAlleenTypeOmvangEnServer() {
        assertEquals("geen foutformaat van NotifyNL (type text/html, 13 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("text/html"), "<h1>fout</h1>"));
        assertEquals("geen foutformaat van NotifyNL (type null, 11 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord(null), "Bad Gateway"));
        assertEquals("geen foutformaat van NotifyNL (type application/json, 7 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), "{\"a\":1}"));
        assertEquals("geen foutformaat van NotifyNL (type application/json, 7 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), "{kapot}"));
    }

    @Test
    void beschrijf_zonderOfMetLegeBody_noemtAlleenTypeOmvangEnServer() {
        assertEquals("geen foutformaat van NotifyNL (type text/plain, 0 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("text/plain"), null));
        assertEquals("geen foutformaat van NotifyNL (type application/json, 2 tekens, server nginx)",
                NotifyNLFoutmelding.beschrijf(antwoord("application/json"), "  "));
    }

    // Een respons die niet (meer) te lezen is, valt terug op de entity als die al tekst is.
    @Test
    void body_onleesbareRespons_valtTerugOpEenTekstEntity() {
        Response tekst = onleesbaar("al gelezen");
        Response anders = onleesbaar(42);

        assertEquals("al gelezen", NotifyNLFoutmelding.body(tekst));
        assertNull(NotifyNLFoutmelding.body(anders));
    }

    private static Response antwoord(String contentType) {
        return Response.status(502).header(HttpHeaders.CONTENT_TYPE, contentType).header("Server", "nginx").build();
    }

    private static Response onleesbaar(Object entity) {
        Response response = Mockito.mock(Response.class);
        Mockito.when(response.readEntity(String.class)).thenThrow(new IllegalStateException("al gelezen"));
        Mockito.when(response.getEntity()).thenReturn(entity);

        return response;
    }
}
