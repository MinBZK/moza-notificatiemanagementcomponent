package nl.rijksoverheid.moz.nmc.client.notifynl;

import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * Carries the per-request JWT to NotifyNLCredentialsProvider, which the generated NotifyNL client
 * consults for the Authorization header.
 */
@RequestScoped
public class NotifyNLAuthorizationHolder {

    private String bearerToken;

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public Optional<String> getBearerToken() {
        return Optional.ofNullable(bearerToken);
    }
}
