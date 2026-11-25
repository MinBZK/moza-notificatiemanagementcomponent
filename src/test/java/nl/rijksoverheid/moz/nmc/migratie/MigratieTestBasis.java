package nl.rijksoverheid.moz.nmc.migratie;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * Basis voor tests die een migratie over bestaande rijen draaien. De gedeelde testdatabase staat al op
 * de laatste versie; zo'n test moet de database op een oudere versie stilzetten en gebruikt daarom een
 * eigen embedded PostgreSQL, met per test een nieuwe database.
 */
abstract class MigratieTestBasis {

    private static final String GEBRUIKER = "postgres";
    private static final String WACHTWOORD = "postgres";

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = EmbeddedPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * Maakt een lege database aan, zodat de Flyway-historie van de ene test de andere niet raakt.
     *
     * @return de JDBC-URL van de nieuwe database
     */
    static String nieuweDatabase() throws SQLException {
        String database = "migratie_" + UUID.randomUUID().toString().replace("-", "");
        String server = "jdbc:postgresql://localhost:" + postgres.getPort() + "/";

        try (Connection beheer = DriverManager.getConnection(server + "postgres", GEBRUIKER, WACHTWOORD);
             var statement = beheer.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }

        return server + database;
    }

    /** Migreert tot en met {@code versie}, of tot de laatste bij {@code "latest"}. */
    static void migreerTot(String jdbcUrl, String versie) {
        migreerTot(jdbcUrl, versie, 0);
    }

    /**
     * Migreert tot en met {@code versie}.
     *
     * @param eventMarge de Flyway-placeholder {@code event_marge}: hoeveel transactie-ids de eerste
     *                   bereikpartitie van het eventlog boven de lopende transactie-id doorloopt
     */
    static void migreerTot(String jdbcUrl, String versie, int eventMarge) {
        Flyway.configure()
                .dataSource(jdbcUrl, GEBRUIKER, WACHTWOORD)
                .locations("classpath:db/migration")
                .placeholders(Map.of("event_marge", String.valueOf(eventMarge)))
                .target(versie)
                .load()
                .migrate();
    }

    static Connection verbind(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, GEBRUIKER, WACHTWOORD);
    }
}
