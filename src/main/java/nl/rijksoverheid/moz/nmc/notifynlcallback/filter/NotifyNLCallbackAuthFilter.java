package nl.rijksoverheid.moz.nmc.notifynlcallback.filter;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Provider
@Priority(Priorities.AUTHENTICATION)
@NotifyNLCallbackBeveiligd
public class NotifyNLCallbackAuthFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] verwachteTokenBytes;

    public NotifyNLCallbackAuthFilter(@ConfigProperty(name = "notify.callback.bearer-token") Optional<String> token) {
        this.verwachteTokenBytes = token.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.callback.bearer-token is niet geconfigureerd"))
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            Log.warn("NotifyNL callback geweigerd: ontbrekend of ongeldig Authorization-header");
            throw Problems.unauthorized("Niet geautoriseerd", "Geldig bearer token vereist");
        }

        String ontvangen = authorizationHeader.substring(BEARER_PREFIX.length());
        if (!tokenGeldig(ontvangen)) {
            Log.warn("NotifyNL callback geweigerd: ongeldig bearer token");
            throw Problems.unauthorized("Niet geautoriseerd", "Geldig bearer token vereist");
        }
    }

    // Constant-time vergelijking om timing-aanvallen te voorkomen
    private boolean tokenGeldig(String ontvangen) {
        return MessageDigest.isEqual(
                verwachteTokenBytes,
                ontvangen.getBytes(StandardCharsets.UTF_8));
    }
}
