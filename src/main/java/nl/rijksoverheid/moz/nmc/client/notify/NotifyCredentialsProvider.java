package nl.rijksoverheid.moz.nmc.client.notify;

import io.quarkiverse.openapi.generator.providers.CredentialsContext;
import io.quarkiverse.openapi.generator.providers.CredentialsProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.Optional;

// Overrides the extension's default ConfigCredentialsProvider (static config), since our bearer
// token is a fresh JWT built per call rather than a fixed property value.
@Alternative
@Priority(200)
@ApplicationScoped
public class NotifyCredentialsProvider implements CredentialsProvider {

    private final NotifyAuthorizationHolder authorizationHolder;

    public NotifyCredentialsProvider(NotifyAuthorizationHolder authorizationHolder) {
        this.authorizationHolder = authorizationHolder;
    }

    @Override
    public Optional<String> getBearerToken(CredentialsContext context) {
        return authorizationHolder.getBearerToken();
    }

    @Override
    public Optional<String> getApiKey(CredentialsContext context) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getBasicUsername(CredentialsContext context) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getBasicPassword(CredentialsContext context) {
        return Optional.empty();
    }

    @Override
    public Optional<String> getOauth2BearerToken(CredentialsContext context) {
        return Optional.empty();
    }
}
