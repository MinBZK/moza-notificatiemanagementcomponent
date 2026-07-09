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
public class NotifyNLCallbackAuthFilter implements ContainerRequestFilter {

    private static final String CALLBACK_PAD = "/api/nmc/v1/notifynl-callback";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String verwachteToken;

    public NotifyNLCallbackAuthFilter(@ConfigProperty(name = "notify.callback.bearer-token") Optional<String> token) {
        this.verwachteToken = token.filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalStateException("notify.callback.bearer-token is niet geconfigureerd"));
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!CALLBACK_PAD.equals(requestContext.getUriInfo().getPath(true))) {
            return;
        }

        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            Log.warn("NotifyNL callback geweigerd: ontbrekend of ongeldig Authorization-header");
            throw Problems.unauthorized("Niet geautoriseerd", "Geldig bearer token vereist");
        }

        String ontvangen = authorizationHeader.substring(BEARER_PREFIX.length());
        if (!tokenGeldig(verwachteToken, ontvangen)) {
            Log.warn("NotifyNL callback geweigerd: ongeldig bearer token");
            throw Problems.unauthorized("Niet geautoriseerd", "Geldig bearer token vereist");
        }
    }

    // Constant-time vergelijking om timing-aanvallen te voorkomen
    private static boolean tokenGeldig(String verwacht, String ontvangen) {
        return MessageDigest.isEqual(
                verwacht.getBytes(StandardCharsets.UTF_8),
                ontvangen.getBytes(StandardCharsets.UTF_8));
    }
}
