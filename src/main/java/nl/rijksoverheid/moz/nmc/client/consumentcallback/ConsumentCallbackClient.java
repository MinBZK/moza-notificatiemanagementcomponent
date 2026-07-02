package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;

// Hand-written REST client (not OpenAPI-generated) — the consumer's callbackUrl is only known
// at runtime, so there's no fixed spec to generate this from.
public interface ConsumentCallbackClient {

    /**
     * POSTs a CloudEvents-formatted notificatie-status update to the consumer-supplied
     * callback URL this client was built for.
     */
    @POST
    @Consumes("application/cloudevents+json")
    void stuurStatusUpdate(NotificatieStatusEvent event);
}
