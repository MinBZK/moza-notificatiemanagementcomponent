package nl.rijksoverheid.moz.nmc.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import nl.rijksoverheid.moz.nmc.helper.HashHelper;

import java.util.Optional;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Tests the HMAC-SHA256 identifier hashing with arbitrary string input. The
 * identifiers that pass through here are BSN/KVK/RSIN values, so a crash or a
 * non-deterministic result would be a problem: the hash is what links a stored
 * notification back to its recipient.
 */
public class HashHelperFuzzer {

    private static final HashHelper hashHelper =
            new HashHelper(Optional.of("fuzz-pepper-niet-voor-productie"));

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String input = data.consumeRemainingAsString();

        String result = hashHelper.hashIdentifier(input);

        // Verify determinism: same input must always produce same output
        String result2 = hashHelper.hashIdentifier(input);
        if (result != null && !result.equals(result2)) {
            throw new AssertionError("Hash is not deterministic!");
        }
    }
}
