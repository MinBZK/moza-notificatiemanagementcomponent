package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.rest.client.reactive.Url;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;

import java.util.List;

/**
 * REST-client voor de webhooks van de Dienstverleners. Eén client voor allemaal; de URL komt per
 * levering uit het register.
 */
public interface ConsumentCallbackClient {

    /** De header met de cursor van het laatste event in de bundel. */
    String CURSOR_HEADER = "Nmc-Cursor";

    /** POST de events als CloudEvents-batch naar de webhook op {@code url}. */
    @POST
    @Consumes("application/cloudevents-batch+json")
    Uni<Void> lever(@Url String url, @HeaderParam("Authorization") String autorisatie,
                    @HeaderParam(CURSOR_HEADER) String cursor, List<NotificatieStatusEvent> events);
}
