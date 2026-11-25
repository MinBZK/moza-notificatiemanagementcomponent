package nl.rijksoverheid.moz.nmc.client.webhook;

import io.quarkus.rest.client.reactive.Url;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import java.io.InputStream;


/**
 * REST-client voor de webhooks van de Dienstverleners. Eén client voor allemaal; de URL komt per
 * levering uit het register.
 */
public interface WebhookClient {

    /** De header met de cursor van het laatste event in de bundel. */
    String CURSOR_HEADER = "Nmc-Cursor";

    /**
     * POST de CloudEvents-batch ({@code body}, al als JSON) naar de webhook op {@code url}.
     *
     * @return de body van het antwoord als stream. Met dit returntype buffert de client het antwoord niet
     *         in het geheugen; de aanroeper sluit de stream zonder hem te lezen
     */
    @POST
    @Consumes("application/cloudevents-batch+json")
    Uni<InputStream> lever(@Url String url, @HeaderParam("Authorization") String autorisatie,
                    @HeaderParam(CURSOR_HEADER) String cursor, String body);
}
