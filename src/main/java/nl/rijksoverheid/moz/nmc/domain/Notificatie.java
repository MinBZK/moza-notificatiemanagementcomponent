package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificatieStatus status;

    @Column(nullable = false)
    private OffsetDateTime aangemaakt;

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        this.status = NotificatieStatus.CREATED;
        this.aangemaakt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public NotificatieStatus getStatus() {
        return status;
    }

    public UUID getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(UUID externalReference) {
        this.externalReference = externalReference;
    }

    public void setStatus(NotificatieStatus status) {
        this.status = status;
    }

    public OffsetDateTime getAangemaakt() {
        return aangemaakt;
    }
}
