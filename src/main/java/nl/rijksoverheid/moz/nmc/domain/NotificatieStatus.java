package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.OffsetDateTime;
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
 *        basis voor de bewaartermijn (zie Notificatie#laatsteStatusUpdate).
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
    }

    /** Registratie op de klok van de NMC zelf: gebeurtenis- en registratietijd vallen dan samen. */
    public static NotificatieStatus opEigenKlok(StatusWaarde status, OffsetDateTime nu) {
        return new NotificatieStatus(status, nu, nu);
    }
}
