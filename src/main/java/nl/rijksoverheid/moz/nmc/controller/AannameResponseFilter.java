package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Maakt van een geslaagde intake een 202 met {@code Location}. De gegenereerde interface geeft een body
 * terug en geen {@code Response}, en een statusannotatie op de controller zou de JAX-RS-annotaties van
 * de interface laten vervallen; daarom hier, op de uitgaande kant.
 */
public class AannameResponseFilter {

    private static final String NOTIFICATIE_PAD = "/api/nmc/v1/notificaties/";

    @ServerResponseFilter
    public void filter(ContainerResponseContext response, ResourceInfo resourceInfo) {
        if (resourceInfo.getResourceMethod() == null || !resourceInfo.getResourceMethod().isAnnotationPresent(Aanname.class)
                || response.getStatus() != Response.Status.OK.getStatusCode()) {
            return;
        }

        response.setStatus(Response.Status.ACCEPTED.getStatusCode());
        if (response.getEntity() instanceof NotificatieResponse aangenomen) {
            response.getHeaders().putSingle(HttpHeaders.LOCATION, NOTIFICATIE_PAD + aangenomen.getNotificatieId());
        }
    }
}
