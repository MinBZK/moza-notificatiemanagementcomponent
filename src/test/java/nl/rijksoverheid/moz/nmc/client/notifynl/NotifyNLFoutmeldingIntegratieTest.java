package nl.rijksoverheid.moz.nmc.client.notifynl;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.client.notifynl.generated.api.SendAMessageApi;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Over echte HTTP, omdat pas daar blijkt of de body van een foutrespons nog te lezen is.
@QuarkusTest
class NotifyNLFoutmeldingIntegratieTest {

    @TestHTTPResource("/test/notifynl")
    URI basis;

    @Inject
    NotifyNLJwtFactory notifyNLJwtFactory;

    @Inject
    NotifyNLAuthorizationHolder notifyNLAuthorizationHolder;

    @Test
    void verstuurEmail_foutVanNotifyNL_noemtErrorEnMessage() {
        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter("auth").verstuurEmail("burger@example.nl", "template", Map.of()));

        assertTrue(e.getMessage().contains("AuthError: Invalid token: API key not found"), e.getMessage());
    }

    @Test
    void verstuurEmail_andereBody_logtAlleenTypeEnServer() {
        NotifyNLVerzendException e = assertThrows(NotifyNLVerzendException.class,
                () -> adapter("gateway").verstuurEmail("burger@example.nl", "template", Map.of()));

        assertTrue(e.getMessage().contains("text/html"), e.getMessage());
        assertTrue(e.getMessage().contains("server nginx"), e.getMessage());
        assertFalse(e.getMessage().contains("burger@example.nl"), e.getMessage());
    }

    private NotifyNLVerzendAdapter adapter(String fout) {
        SendAMessageApi client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(basis + "/" + fout))
                .build(SendAMessageApi.class);

        return new NotifyNLVerzendAdapter(client, notifyNLJwtFactory, notifyNLAuthorizationHolder,
                Optional.of("niet-voor-productie-00000000-0000-0000-0000-000000000000-11111111-1111-1111-1111-111111111111"));
    }
}
