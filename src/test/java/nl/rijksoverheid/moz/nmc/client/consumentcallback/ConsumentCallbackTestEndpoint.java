package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Nepontvanger van een Dienstverlener: antwoordt met de HTTP-status uit het pad. */
@Path("/test/consument-callback")
public class ConsumentCallbackTestEndpoint {

    static final List<String> ONTVANGEN_CONTENT_TYPES = new CopyOnWriteArrayList<>();
    static final List<String> ONTVANGEN_BODIES = new CopyOnWriteArrayList<>();

    @POST
    @Path("/{status}")
    public Response ontvang(@PathParam("status") int status, @HeaderParam("Content-Type") String contentType,
                            String body) {
        ONTVANGEN_CONTENT_TYPES.add(contentType);
        ONTVANGEN_BODIES.add(body);

        return Response.status(status).header("Location", "https://elders.example.nl/callback").build();
    }
}
