package nl.rijksoverheid.moz.nmc;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Start een echte PostgreSQL als kindproces van de test-JVM, zonder Docker, zodat de tests de
 * Flyway-migraties en de queries op het productiedialect draaien.
 */
public class EmbeddedPostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private EmbeddedPostgres postgres;

    @Override
    public Map<String, String> start() {
        try {
            postgres = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Embedded PostgreSQL kon niet starten", e);
        }

        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";

        // De logboekinzage leest in de tests uit dezelfde database; de wrapper zelf blijft uit.
        return Map.of(
                "quarkus.datasource.jdbc.url", url,
                "quarkus.datasource.username", "postgres",
                "quarkus.datasource.password", "postgres",
                "logboekdataverwerking.postgresql.url", url,
                "logboekdataverwerking.postgresql.username", "postgres",
                "logboekdataverwerking.postgresql.password", "postgres",
                "logboekdataverwerking.postgresql.table", "logboek_spans");
    }

    @Override
    public void stop() {
        if (postgres == null) {
            return;
        }

        try {
            postgres.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
