package nl.rijksoverheid.moz.nmc.controller;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
public class ApiVersionFilter implements ContainerResponseFilter {

    private final String version;

    public ApiVersionFilter(@ConfigProperty(name = "mp.openapi.info.version") String version) {
        this.version = version;
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().add("API-Version", version);
    }
}
