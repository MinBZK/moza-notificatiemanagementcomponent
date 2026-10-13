package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ConsumentCallbackClientProducer {

    // Time-out voor de verbinding en tussen twee stukken antwoord; de totale duur bewaakt de adapter.
    @Produces
    @ApplicationScoped
    ConsumentCallbackClient consumentCallbackClient(@ConfigProperty(name = "nmc.webhook.timeout") Duration timeout) {
        // De builder eist een basis-URL; @Url op lever vervangt hem bij elke aanroep. Een .invalid-naam
        // resolvet nooit.
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create("https://webhook.invalid"))
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .register(GeenSuccesAntwoordMapper.class)
                .build(ConsumentCallbackClient.class);
    }

    void sluit(@Disposes ConsumentCallbackClient client) throws IOException {
        if (client instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
