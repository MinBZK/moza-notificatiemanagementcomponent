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
        return Map.of(
                "notificatie.retentie.bewaartermijn", DAGEN + "d",
                // Een eigen database, en niet de gedeelde jdbc:h2:mem:nmc van %test. Een @TestProfile
                // laat Quarkus herstarten, en die herstart tegen de al bestaande gedeelde database
                // liet Notificatie-rijen met een lege laatste_status insertten — de CHECK-constraint
                // klapte er dan op. Met een eigen database doet hetzelfde codepad het wel, dus het is
                // een interactie tussen de herstart en de gedeelde in-memory database en niet iets in
                // de @Embedded-projectie zelf. Een echte deploy start tegen een bestaande PostgreSQL
                // en raakt dit niet.
                "quarkus.datasource.jdbc.url", "jdbc:h2:mem:nmc-bewaartermijn;DB_CLOSE_DELAY=-1");
    }
}
