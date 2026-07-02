package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkiverse.openapi.generator.providers.CredentialsContext;
import io.quarkiverse.openapi.generator.providers.CredentialsProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class NotifyNLCredentialsProviderTest {

    @Inject
    CredentialsProvider credentialsProvider;

    @Test
    void credentialsProvider_overridtExtensieDefault() {
        // Zonder de @Alternative @Priority-override in NotifyNLCredentialsProvider zou de extensie's
        // eigen config-gebaseerde ConfigCredentialsProvider gebruikt worden, en gaat NotifyNL stilletjes
        // zonder bearer token.
        assertInstanceOf(NotifyNLCredentialsProvider.class, credentialsProvider);
    }

    @Test
    void getBearerToken_retourneertTokenUitHolder() {
        NotifyNLAuthorizationHolder holder = new NotifyNLAuthorizationHolder();
        holder.setBearerToken("test-token");
        NotifyNLCredentialsProvider provider = new NotifyNLCredentialsProvider(holder);

        assertEquals("test-token", provider.getBearerToken(dummyContext()).orElse(null));
    }

    @Test
    void getBearerToken_zonderToken_retourneertLeeg() {
        NotifyNLCredentialsProvider provider = new NotifyNLCredentialsProvider(new NotifyNLAuthorizationHolder());

        assertTrue(provider.getBearerToken(dummyContext()).isEmpty());
    }

    @Test
    void overigeCredentialMethoden_retourenAltijdLeeg() {
        NotifyNLCredentialsProvider provider = new NotifyNLCredentialsProvider(new NotifyNLAuthorizationHolder());
        CredentialsContext context = dummyContext();

        assertTrue(provider.getApiKey(context).isEmpty());
        assertTrue(provider.getBasicUsername(context).isEmpty());
        assertTrue(provider.getBasicPassword(context).isEmpty());
        assertTrue(provider.getOauth2BearerToken(context).isEmpty());
    }

    private CredentialsContext dummyContext() {
        return CredentialsContext.builder()
                .openApiSpecId("notifynl_api_yaml")
                .authName("BearerAuth")
                .build();
    }
}
