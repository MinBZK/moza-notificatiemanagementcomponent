package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Eén verzending van een notificatie bij NotifyNL. Het id gaat als {@code reference} mee in het
 * verzendverzoek, zodat een receipt ook zonder NotifyNL-id bij de poging terechtkomt.
 */
@Entity
public class Poging {

    @Id
    private UUID id;

    @Column(name = "notificatie_id", nullable = false)
    private UUID notificatieId;

    @Column(nullable = false)
    private int nummer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PogingStatus status;

    @Column(name = "notify_id")
    private UUID notifyId;

    // NotifyNL-id's van een dubbele verzending na een herclaim; zelfde ontvanger en inhoud.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "duplicaat_ids", nullable = false)
    private List<UUID> duplicaatIds = new ArrayList<>();

    @Column(name = "verzonden_op")
    private OffsetDateTime verzondenOp;

    @Column(name = "receipt_tijdstip")
    private OffsetDateTime receiptTijdstip;

    protected Poging() {
        // Voor JPA
    }

    public Poging(UUID notificatieId, int nummer) {
        this.id = UUID.randomUUID();
        this.notificatieId = Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        this.nummer = nummer;
        this.status = PogingStatus.GEPLAND;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificatieId() {
        return notificatieId;
    }

    public int getNummer() {
        return nummer;
    }

    public PogingStatus getStatus() {
        return status;
    }

    public UUID getNotifyId() {
        return notifyId;
    }

    public List<UUID> getDuplicaatIds() {
        return List.copyOf(duplicaatIds);
    }

    public OffsetDateTime getVerzondenOp() {
        return verzondenOp;
    }

    public OffsetDateTime getReceiptTijdstip() {
        return receiptTijdstip;
    }
}
