package nl.rijksoverheid.moz.nmc.client.webhook;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class WebhookClientProducer {

    /**
     * De time-out geldt voor het opbouwen van de verbinding en voor de stilte tussen twee stukken
     * antwoord, niet voor de aanroep als geheel. De totale duur begrenst {@link WebhookAdapter}.
     */
    @Produces
    @ApplicationScoped
    WebhookClient webhookClient(WebhookConfig config) {
        Duration timeout = config.timeout();

        // De builder eist een basis-URL; @Url op lever vervangt hem bij elke aanroep. Een .invalid-naam
        // resolvet nooit.
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create("https://webhook.invalid"))
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .register(GeenSuccesAntwoordMapper.class)
                .build(WebhookClient.class);
    }

    // Een MicroProfile REST-client implementeert altijd Closeable.
    void sluit(@Disposes WebhookClient client) throws IOException {
        ((Closeable) client).close();
    }
}
