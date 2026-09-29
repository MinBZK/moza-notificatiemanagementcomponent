package nl.rijksoverheid.moz.nmc.testhelper;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nepwebhook van een Dienstverlener in de test-applicatie: legt elke aanroep vast en antwoordt met de
 * HTTP-status uit het pad. Een 3xx krijgt een Location mee, zodat te zien is dat hij niet gevolgd wordt.
 */
@Path("/test/webhook")
public class WebhookOntvanger {

    public static final List<Aanroep> AANROEPEN = new CopyOnWriteArrayList<>();

    /** Wat de webhook binnenkreeg. */
    public record Aanroep(String contentType, String autorisatie, String cursor, String body) {
    }

    @POST
    @Path("/{status}")
    public Response ontvang(@PathParam("status") int status, @HeaderParam("Content-Type") String contentType,
                            @HeaderParam("Authorization") String autorisatie, @HeaderParam("Nmc-Cursor") String cursor,
                            String body) {
        AANROEPEN.add(new Aanroep(contentType, autorisatie, cursor, body));

        return Response.status(status).header("Location", "https://elders.example.nl/webhook").build();
    }
}
