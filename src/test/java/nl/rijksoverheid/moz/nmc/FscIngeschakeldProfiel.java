package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub;
import nl.rijksoverheid.moz.nmc.testhelper.FscManagerStub.Sleutel;

import java.util.List;
import java.util.Map;

/**
 * Testprofiel met de controle van het FSC-toegangstoken aan, tegen een {@link FscManagerStub}. De
 * test ondertekent zijn tokens met de sleutel uit {@link #PRIVATE_SLEUTEL}.
 */
public class FscIngeschakeldProfiel implements QuarkusTestProfile {

    /** De configuratiesleutel met de private sleutel van de nep-Manager, als base64 van PKCS#8. */
    public static final String PRIVATE_SLEUTEL = "test.fsc.private-sleutel";

    public static final String DUIM = "test-duim-niet-voor-productie";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "nmc.fsc.ingeschakeld", "true",
                "nmc.fsc.uitgever", FscManagerStub.UITGEVER,
                "nmc.fsc.inway-adres", FscManagerStub.INWAY_ADRES,
                "nmc.fsc.groep", FscManagerStub.GROEP,
                "nmc.fsc.dienst", FscManagerStub.DIENST);
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(ManagerResource.class));
    }

    /**
     * Start de nep-Manager. De testresource en de test delen geen statische velden, dus de sleutel
     * gaat via de configuratie naar de test.
     */
    public static class ManagerResource implements QuarkusTestResourceLifecycleManager {

        private FscManagerStub manager;

        @Override
        public Map<String, String> start() {
            manager = new FscManagerStub();
            Sleutel sleutel = Sleutel.rsa(DUIM);
            manager.publiceer(sleutel);

            return Map.of(
                    "nmc.fsc.jwks-url", manager.jwksUrl().toString(),
                    PRIVATE_SLEUTEL, sleutel.privaatBase64());
        }

        @Override
        public void stop() {
            if (manager != null) {
                manager.close();
            }
        }
    }
}
