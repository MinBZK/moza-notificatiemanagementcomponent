package nl.rijksoverheid.moz.nmc.client.consumentcallback;

@FunctionalInterface
public interface ConsumentCallbackClientFactory {
    ConsumentCallbackClient maakClient(String url);
}
