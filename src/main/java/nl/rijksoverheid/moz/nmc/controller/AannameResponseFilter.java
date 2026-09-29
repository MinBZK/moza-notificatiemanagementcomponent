package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Maakt van een geslaagde intake een 202 met {@code Location}. De gegenereerde interface geeft een body
 * terug en geen {@code Response}, en een statusannotatie op de controller zou de JAX-RS-annotaties van
 * de interface laten vervallen; daarom hier, op de uitgaande kant.
 */
public class AannameResponseFilter {

    private final AannameLocatie aannameLocatie;

    public AannameResponseFilter(AannameLocatie aannameLocatie) {
        this.aannameLocatie = aannameLocatie;
    }

    @ServerResponseFilter
    public void filter(ContainerResponseContext response, ResourceInfo resourceInfo) {
        if (resourceInfo.getResourceMethod() == null || !resourceInfo.getResourceMethod().isAnnotationPresent(Aanname.class)
                || response.getStatus() != Response.Status.OK.getStatusCode()) {
            return;
        }

        response.setStatus(Response.Status.ACCEPTED.getStatusCode());
        aannameLocatie.pad().ifPresent(pad -> response.getHeaders().putSingle(HttpHeaders.LOCATION, pad));
    }
}
