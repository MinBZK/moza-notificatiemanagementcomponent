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
 * Eén verzending van een notificatie bij NotifyNL. Een receipt wordt via het NotifyNL-id aan de
 * poging gekoppeld.
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

    // Het tijdstip uit de eerste verwerkte delivered-receipt; een latere faalreceipt laat het staan.
    @Column(name = "bezorgd_op")
    private OffsetDateTime bezorgdOp;

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

    /** Het tijdstip van de bezorging waarop de vaststellingstermijn loopt; null als er geen delivered was. */
    public OffsetDateTime getBezorgdOp() {
        return bezorgdOp;
    }

    /**
     * Legt vast dat NotifyNL de poging heeft aangenomen.
     *
     * @throws IllegalStateException als de poging niet meer op {@code GEPLAND} staat
     */
    public void markeerVerzonden(UUID notifyId, OffsetDateTime op) {
        Objects.requireNonNull(notifyId, "notifyId is verplicht");

        if (status != PogingStatus.GEPLAND) {
            throw new IllegalStateException("Poging " + id + " is al verzonden (status " + status + ")");
        }

        this.notifyId = notifyId;
        this.verzondenOp = Objects.requireNonNull(op, "op is verplicht");
        this.status = PogingStatus.VERZONDEN;
    }

    /** Of een receipt met dit NotifyNL-id over deze poging gaat: het eigen id of een duplicaat. */
    public boolean hoortBij(UUID notifyId) {
        return notifyId.equals(this.notifyId) || duplicaatIds.contains(notifyId);
    }

    /**
     * Legt een tweede NotifyNL-id vast: een dubbele verzending na een herclaim, met dezelfde ontvanger
     * en inhoud. Receipts voor dat id worden verwerkt alsof ze deze poging betreffen.
     */
    public void registreerDuplicaat(UUID notifyId) {
        Objects.requireNonNull(notifyId, "notifyId is verplicht");

        if (this.notifyId == null) {
            throw new IllegalStateException("Poging " + id + " heeft nog geen NotifyNL-id; een duplicaat kan pas daarna");
        }

        if (!hoortBij(notifyId)) {
            duplicaatIds.add(notifyId);
        }
    }

    /**
     * Sluit de navraag af zonder uitkomst: NotifyNL kent de verzending niet meer. Een later
     * binnengekomen receipt mag de poging nog corrigeren.
     *
     * @throws IllegalStateException als de poging niet op {@code VERZONDEN} staat
     */
    public void markeerOnbekend() {
        if (status != PogingStatus.VERZONDEN) {
            throw new IllegalStateException("Poging " + id + " staat op " + status + " en kan niet onbekend worden");
        }

        this.status = PogingStatus.ONBEKEND;
    }

    /**
     * Legt de uitkomst uit een receipt vast als die nieuwer is dan de laatst vastgelegde. Receipts
     * komen ongeordend binnen; de volgorde komt uit het tijdstip in de receipt zelf.
     *
     * @return false als de receipt een herhaling of ouder is en dus niets heeft veranderd
     */
    public boolean verwerkReceipt(PogingStatus uitkomst, OffsetDateTime tijdstip) {
        Objects.requireNonNull(uitkomst, "uitkomst is verplicht");
        Objects.requireNonNull(tijdstip, "tijdstip is verplicht");

        if (receiptTijdstip != null && !tijdstip.isAfter(receiptTijdstip)) {
            return false;
        }

        this.status = uitkomst;
        this.receiptTijdstip = tijdstip;

        if (uitkomst == PogingStatus.BEZORGD && bezorgdOp == null) {
            this.bezorgdOp = tijdstip;
        }

        return true;
    }
}
