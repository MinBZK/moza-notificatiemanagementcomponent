package nl.rijksoverheid.moz.client.consumentcallback;

@FunctionalInterface
public interface ConsumentCallbackClientFactory {
    ConsumentCallbackClient maakClient(String url);
}
