package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Zet een bewaartermijn die van de geconfigureerde afwijkt, zodat een test kan aantonen dát de
 * property het gedrag stuurt. Zonder een afwijkende waarde zou een hardgecodeerde grens in de
 * scheduler alle tests even groen laten: de overige schedulertests gebruiken marges van 31 dagen
 * tegen 1 dag en slagen daarmee bij vrijwel elke termijn.
 */
public class BewaartermijnTestProfile implements QuarkusTestProfile {

    static final int DAGEN = 3;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("notificatie.retentie.bewaartermijn", DAGEN + "d");
    }
}
