package nl.rijksoverheid.moz.client.consumentcallback;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class QuarkusConsumentCallbackClientFactoryTest {

    @Inject
    QuarkusConsumentCallbackClientFactory factory;

    @Test
    void maakClient_retourneertNietNulleClient() {
        ConsumentCallbackClient client = factory.maakClient("http://localhost:8080");

        assertNotNull(client);
    }
}
