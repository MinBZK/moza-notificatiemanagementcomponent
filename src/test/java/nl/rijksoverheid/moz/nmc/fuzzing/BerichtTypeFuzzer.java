package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import nl.rijksoverheid.moz.nmc.service.BerichtType;
import nl.rijksoverheid.moz.nmc.service.OnbekendBerichtTypeException;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * The berichtType field comes straight from the caller and is resolved to a
 * NotifyNL template via this lookup. Anything other than a known type must end
 * up as OnbekendBerichtTypeException (which the controller turns into a 400) —
 * never as an unexpected runtime exception.
 */
public class BerichtTypeFuzzer {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String naam = data.consumeRemainingAsString();
        try {
            BerichtType.vanNaam(naam);
        } catch (OnbekendBerichtTypeException e) {
            // Expected for every input that is not a known berichttype
        }
    }
}
