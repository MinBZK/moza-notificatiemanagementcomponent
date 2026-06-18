package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;

public interface ConsumentCallbackClient {

    @POST
    @Consumes("application/cloudevents+json")
    void stuurStatusUpdate(NotificatieStatusEvent event);
}
