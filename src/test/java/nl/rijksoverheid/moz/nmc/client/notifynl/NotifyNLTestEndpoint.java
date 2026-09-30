package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Nep-NotifyNL: antwoordt op het verzendpad met de fout uit het pad. */
@Path("/test/notifynl/{fout}")
public class NotifyNLTestEndpoint {

    @POST
    @Path("/v2/notifications/email")
    public Response verstuur(@PathParam("fout") String fout) {
        return switch (fout) {
            case "auth" -> Response.status(403).type("application/json")
                    .entity("{\"errors\":[{\"error\":\"AuthError\",\"message\":\"Invalid token: API key not found\"}],\"status_code\":403}")
                    .build();
            default -> Response.status(403).type("text/html").header("Server", "nginx")
                    .entity("<html><body>Forbidden burger@example.nl</body></html>")
                    .build();
        };
    }
}
