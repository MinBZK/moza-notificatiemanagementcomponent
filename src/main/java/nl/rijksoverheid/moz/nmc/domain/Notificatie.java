package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
public class Notificatie {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "external_reference", unique = true)
    private UUID externalReference;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    @ElementCollection
    @CollectionTable(name = "notificatie_status", joinColumns = @JoinColumn(name = "notificatie_id"))
    @OrderBy("tijdstip ASC")
    private List<NotificatieStatus> statusGeschiedenis = new ArrayList<>();

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        registreerStatus(StatusWaarde.CREATED, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public UUID getId() {
        return id;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    // Afgeleid van het laatste statusGeschiedenis-record, dus vereist een actieve persistence
    // context (net als getStatusGeschiedenis()).
    public StatusWaarde getStatus() {
        return laatsteStatus().status();
    }

    public UUID getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(UUID externalReference) {
        this.externalReference = externalReference;
    }

    public void registreerStatus(StatusWaarde status) {
        registreerStatus(status, OffsetDateTime.now(ZoneOffset.UTC));
    }

    // Enige mutatiepunt voor status: statusGeschiedenis is de enige bron van waarheid, getStatus()
    // en getLaatsteStatusUpdate() lezen hier gewoon van af.
    private void registreerStatus(StatusWaarde status, OffsetDateTime tijdstip) {
        this.statusGeschiedenis.add(new NotificatieStatus(status, tijdstip));
    }

    // Afgeleid van het eerste statusGeschiedenis-record (altijd CREATED, zie de constructor) —
    // vereist een actieve persistence context.
    public OffsetDateTime getAangemaakt() {
        return statusGeschiedenis.get(0).tijdstip();
    }

    // Afgeleid van het laatste statusGeschiedenis-record — vereist een actieve persistence context.
    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatus().tijdstip();
    }

    // Vereist een actieve persistence context: statusGeschiedenis is een lazy @ElementCollection.
    public List<NotificatieStatus> getStatusGeschiedenis() {
        return List.copyOf(statusGeschiedenis);
    }

    private NotificatieStatus laatsteStatus() {
        return statusGeschiedenis.get(statusGeschiedenis.size() - 1);
    }
}
