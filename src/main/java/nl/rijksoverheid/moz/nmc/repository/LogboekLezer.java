package nl.rijksoverheid.moz.nmc.repository;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Leest het Logboek Dataverwerkingen uit de PostgreSQL-backend van de LDV-wrapper, die zelf alleen
 * schrijft. Gebruikt dezelfde verbindingsgegevens als de wrapper; een verbinding per inzage, omdat
 * de inzage zelden wordt aangeroepen en zo geen pool naast die van de NMC nodig is.
 */
@ApplicationScoped
public class LogboekLezer {

    // De tabelnaam komt uit config en gaat letterlijk in de SQL; daarom alleen een (schema.)naam.
    private static final Pattern TABELNAAM = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private final Optional<String> url;
    private final Optional<String> gebruiker;
    private final Optional<String> wachtwoord;
    private final String tabel;

    public LogboekLezer(@ConfigProperty(name = "logboekdataverwerking.postgresql.url") Optional<String> url,
                        @ConfigProperty(name = "logboekdataverwerking.postgresql.username") Optional<String> gebruiker,
                        @ConfigProperty(name = "logboekdataverwerking.postgresql.password") Optional<String> wachtwoord,
                        @ConfigProperty(name = "logboekdataverwerking.postgresql.table", defaultValue = "spans") String tabel) {
        if (!TABELNAAM.matcher(tabel).matches()) {
            throw new IllegalStateException("logboekdataverwerking.postgresql.table is geen geldige tabelnaam");
        }
        this.url = url.filter(s -> !s.isBlank());
        this.gebruiker = gebruiker;
        this.wachtwoord = wachtwoord;
        this.tabel = tabel;
    }

    /** Alle rijen van één betrokkene, eventueel binnen [van, tot). */
    public List<LogboekRij> lees(String betrokkeneType, String betrokkeneHash, Instant van, Instant tot) {
        String jdbcUrl = url.orElseThrow(() -> new LogboekNietLeesbaarException(
                "logboekdataverwerking.postgresql.url is niet geconfigureerd", null));
        StringBuilder sql = new StringBuilder("""
                SELECT trace_id, span_id, parent_span_id, status, name, start_time,
                       attributes->>'dpl.core.processing_activity_id' AS activiteit,
                       attributes->>'moza.ldv.uitkomst' AS uitkomst,
                       attributes->>'exception.type' AS fout_type,
                       attributes->>'exception.message' AS fout_melding
                FROM %s
                WHERE attributes->>'dpl.core.data_subject_id' = ?
                  AND attributes->>'dpl.core.data_subject_id_type' = ?
                """.formatted(tabel));
        if (van != null) {
            sql.append(" AND start_time >= ?");
        }
        if (tot != null) {
            sql.append(" AND start_time < ?");
        }

        try (Connection verbinding = DriverManager.getConnection(jdbcUrl, gebruiker.orElse(null), wachtwoord.orElse(null));
             PreparedStatement statement = verbinding.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, betrokkeneHash);
            statement.setString(index++, betrokkeneType);
            if (van != null) {
                statement.setLong(index++, van.toEpochMilli());
            }
            if (tot != null) {
                statement.setLong(index, tot.toEpochMilli());
            }

            List<LogboekRij> rijen = new ArrayList<>();
            try (ResultSet resultaat = statement.executeQuery()) {
                while (resultaat.next()) {
                    rijen.add(new LogboekRij(resultaat.getString("trace_id"), resultaat.getString("span_id"),
                            resultaat.getString("parent_span_id"), resultaat.getString("status"),
                            resultaat.getString("name"), Instant.ofEpochMilli(resultaat.getLong("start_time")),
                            resultaat.getString("activiteit"), resultaat.getString("uitkomst"),
                            resultaat.getString("fout_type"), resultaat.getString("fout_melding")));
                }
            }

            return rijen;
        } catch (SQLException e) {
            throw new LogboekNietLeesbaarException("Het logboek kon niet worden gelezen", e);
        }
    }

    /** Eén rij uit de logboektabel, met alleen wat de inzage gebruikt. */
    public record LogboekRij(String traceId, String spanId, String parentSpanId, String status, String naam,
                             Instant start, String activiteit, String uitkomstAttribuut, String foutType,
                             String foutMelding) {
    }

    public static class LogboekNietLeesbaarException extends RuntimeException {
        public LogboekNietLeesbaarException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
