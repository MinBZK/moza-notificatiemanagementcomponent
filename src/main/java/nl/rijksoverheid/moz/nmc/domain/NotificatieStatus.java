package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.OffsetDateTime;
import java.util.Objects;

/** De status van een Notificatie op een gegeven moment. Waarde-object, eigendom van één Notificatie. */
@Embeddable
public record NotificatieStatus(
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 32)
        StatusWaarde status,

        @Column(nullable = false)
        OffsetDateTime tijdstip) {

    public NotificatieStatus {
        Objects.requireNonNull(status, "status is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");
    }
}
