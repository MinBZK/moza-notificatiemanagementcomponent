package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;

@ApplicationScoped
public class QuarkusConsumentCallbackClientFactory implements ConsumentCallbackClientFactory {

    @Override
    public ConsumentCallbackClient maakClient(String url) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .build(ConsumentCallbackClient.class);
    }
}
