package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.rijksoverheid.moz.nmc.api.model.DecentraleNotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieAanvraagRequest;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.notifynlcallback.api.model.AfleverstatusRequest;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Tests JSON deserialization of the request and response DTOs with arbitrary input.
 * These are the types that carry data straight from an untrusted caller (or from
 * NotifyNL's webhook) into the application.
 */
public class JsonDeserializationFuzzer {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Class<?>[] MESSAGE_TYPES = {
        NotificatieAanvraagRequest.class,
        DecentraleNotificatieAanvraagRequest.class,
        AfleverstatusRequest.class,
        NotificatieResponse.class,
    };

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        Class<?> targetType = data.pickValue(MESSAGE_TYPES);
        String json = data.consumeRemainingAsString();
        try {
            mapper.readValue(json, targetType);
        } catch (Exception e) {
            // Expected for invalid JSON or incompatible types
        }
    }
}
