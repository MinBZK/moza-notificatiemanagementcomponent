package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Bouwt per levering een client; de time-out houdt een trage webhook binnen de lease van de taak. */
@ApplicationScoped
public class QuarkusConsumentCallbackClientFactory implements ConsumentCallbackClientFactory {

    private final Duration timeout;

    public QuarkusConsumentCallbackClientFactory(@ConfigProperty(name = "nmc.webhook.timeout") Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public ConsumentCallbackClient maakClient(String url) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .register(GeenSuccesAntwoordMapper.class)
                .build(ConsumentCallbackClient.class);
    }
}
