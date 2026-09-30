package nl.rijksoverheid.moz.nmc.client.notifynl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Vat een foutrespons van NotifyNL samen voor de log. Uit het foutformaat van NotifyNL
 * ({@code {"errors":[{"error":..., "message":...}]}}) komen alleen {@code error} en {@code message}; die
 * noemen het veld, niet de waarde. Een andere body, bijvoorbeeld van een gateway ervoor, wordt niet
 * gelogd: dan alleen het type, de omvang en de {@code Server}-header.
 */
final class NotifyNLFoutmelding {

    static final int MAX_LENGTE = 200;
    private static final int MAX_FOUTEN = 5;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private NotifyNLFoutmelding() {
    }

    static String beschrijf(Response response) {
        String body = body(response);
        List<String> fouten = notifyFouten(body);

        if (!fouten.isEmpty()) {
            return String.join("; ", fouten);
        }

        return "geen foutformaat van NotifyNL (type " + response.getHeaderString(HttpHeaders.CONTENT_TYPE)
                + ", " + (body == null ? 0 : body.length()) + " tekens, server "
                + response.getHeaderString("Server") + ")";
    }

    private static List<String> notifyFouten(String body) {
        List<String> fouten = new ArrayList<>();

        if (body == null || body.isBlank()) {
            return fouten;
        }

        try {
            for (JsonNode fout : OBJECT_MAPPER.readTree(body).path("errors")) {
                if (fouten.size() == MAX_FOUTEN) {
                    break;
                }

                fouten.add(schoon(fout.path("error").asText()) + ": " + schoon(fout.path("message").asText()));
            }
        } catch (JsonProcessingException e) {
            fouten.clear();
        }

        return fouten;
    }

    private static String body(Response response) {
        try {
            return response.readEntity(String.class);
        } catch (RuntimeException e) {
            return response.getEntity() instanceof String tekst ? tekst : null;
        }
    }

    // Geen regeleinden of stuurtekens in de log, en een begrensde lengte.
    private static String schoon(String tekst) {
        String zonderStuurtekens = tekst.replaceAll("\\p{Cntrl}", " ");

        return zonderStuurtekens.length() > MAX_LENGTE ? zonderStuurtekens.substring(0, MAX_LENGTE) + "…" : zonderStuurtekens;
    }
}
