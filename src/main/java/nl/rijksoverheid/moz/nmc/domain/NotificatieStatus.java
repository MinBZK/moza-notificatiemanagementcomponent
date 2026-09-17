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
 * @param tijdstip wanneer de status is ontstaan. Voor een delivery receipt van NotifyNL is dat hun
 *        completed_at (met sent_at en created_at als terugval), niet het moment waarop de NMC de
 *        callback ontving: NotifyNL herhaalt een callback tot 5x met 5 minuten ertussen, dus die
 *        twee kunnen tientallen minuten uiteenlopen. Dit is het tijdstip voor het afleverbewijs, en
 *        het staat op de klok van NotifyNL.
 * @param geregistreerd wanneer de NMC de status vastlegde, op de eigen klok. Monotoon, en daarom de
 *        basis voor de bewaartermijn (kolom laatste_status_update).
 */
@Embeddable
public record NotificatieStatus(
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        StatusWaarde status,

        @Column(nullable = false)
        OffsetDateTime tijdstip,

        @Column(nullable = false)
        OffsetDateTime geregistreerd) {

    public NotificatieStatus {
        Objects.requireNonNull(status, "status is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");
        Objects.requireNonNull(geregistreerd, "geregistreerd is verplicht");

        // Normaliseren naar UTC en afkappen op microseconden, zodat de waarde hier gelijk is aan de
        // waarde die uit de database terugkomt. PostgreSQL levert een timestamptz altijd als UTC
        // terug terwijl H2 de offset bewaart, en OffsetDateTime#equals eist dezelfde offset; de
        // kolommen zijn timestamp(6), dus de nanoseconden van OffsetDateTime#now overleven niet.
        tijdstip = tijdstip.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        geregistreerd = geregistreerd.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** Registratie op de klok van de NMC zelf: gebeurtenis- en registratietijd vallen dan samen. */
    public static NotificatieStatus opEigenKlok(StatusWaarde status, OffsetDateTime nu) {
        return new NotificatieStatus(status, nu, nu);
    }
}
