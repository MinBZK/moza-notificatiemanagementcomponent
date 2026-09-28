package nl.rijksoverheid.moz.nmc.helper;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.core.Response;

import java.net.URI;

public final class Problems {

    /** Probleemtype van een vervallen feedcursor (410); staat ook in openapi.yaml. */
    public static final URI TYPE_CURSOR_VERVALLEN = URI.create("https://mijnoverheidzakelijk.nl/nmc/problemen/cursor-vervallen");

    /** Probleemtype van een overschreden aanroeplimiet op de feed (429); staat ook in openapi.yaml. */
    public static final URI TYPE_AANROEPLIMIET_OVERSCHREDEN = URI.create("https://mijnoverheidzakelijk.nl/nmc/problemen/aanroeplimiet-overschreden");

    private Problems() {
    }

    public static HttpProblem unauthorized(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.UNAUTHORIZED)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem badRequest(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.BAD_REQUEST)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem notFound(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.NOT_FOUND)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem gone(URI type, String title, String detail) {
        return HttpProblem.builder()
                .withType(type)
                .withStatus(Response.Status.GONE)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem tooManyRequests(URI type, String title, String detail) {
        return HttpProblem.builder()
                .withType(type)
                .withStatus(Response.Status.TOO_MANY_REQUESTS)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem badGateway(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.BAD_GATEWAY)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem serverError(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.INTERNAL_SERVER_ERROR)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }
}
