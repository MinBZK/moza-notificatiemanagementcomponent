package nl.rijksoverheid.moz.nmc.helper;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.core.Response;

import java.net.URI;

public final class Problems {

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

    public static HttpProblem badGateway(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.BAD_GATEWAY)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }

    /** 429 met een eigen probleemtype, zodat een Dienstverlener het quotum van andere fouten onderscheidt. */
    public static HttpProblem quotumOverschreden(String detail) {
        return HttpProblem.builder()
                .withType(URI.create("https://mijnoverheidzakelijk.nl/problemen/quotum-overschreden"))
                .withStatus(Response.Status.TOO_MANY_REQUESTS)
                .withTitle("Quotum overschreden")
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
