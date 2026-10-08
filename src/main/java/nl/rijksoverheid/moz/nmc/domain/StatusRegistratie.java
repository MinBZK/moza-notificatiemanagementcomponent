package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * De status van een Notificatie op een gegeven moment. Waarde-object, eigendom van één Notificatie.
 *
 * @param status de status zelf
 * @param tijdstip wanneer de status ontstond, op de klok van de bron; voor een delivery receipt de
 *        completed_at van NotifyNL, niet het moment van ontvangst
 * @param geregistreerd wanneer de NMC de status vastlegde, op de eigen klok (kolom
 *        {@code geregistreerd}; in de projectie {@code laatste_status_update})
 */
@Embeddable
public record StatusRegistratie(
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        StatusWaarde status,

        @Column(nullable = false)
        OffsetDateTime tijdstip,

        @Column(nullable = false)
        OffsetDateTime geregistreerd) {

    public StatusRegistratie {
        Objects.requireNonNull(status, "status is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");
        Objects.requireNonNull(geregistreerd, "geregistreerd is verplicht");

        // Normaliseren naar UTC en afkappen op microseconden, zodat de waarde hier gelijk is aan de
        // waarde die uit de database terugkomt. PostgreSQL levert een timestamptz altijd als UTC
        // terug en OffsetDateTime#equals eist dezelfde offset; de kolommen zijn timestamp(6), dus
        // de nanoseconden van OffsetDateTime#now overleven niet.
        tijdstip = tijdstip.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        geregistreerd = geregistreerd.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** Registratie op de klok van de NMC zelf: gebeurtenis- en registratietijd vallen dan samen. */
    public static StatusRegistratie opEigenKlok(StatusWaarde status, OffsetDateTime nu) {
        return new StatusRegistratie(status, nu, nu);
    }
}
