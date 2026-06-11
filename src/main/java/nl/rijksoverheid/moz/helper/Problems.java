package nl.rijksoverheid.moz.helper;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.core.Response;

public final class Problems {

    private Problems() {
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

    public static HttpProblem serverError(String title, String detail) {
        return HttpProblem.builder()
                .withStatus(Response.Status.INTERNAL_SERVER_ERROR)
                .withTitle(title)
                .withDetail(detail)
                .build();
    }
}
