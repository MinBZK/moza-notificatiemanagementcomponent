package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
public class Notificatie {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "notifynl_notificatie_id", unique = true)
    private UUID notifyNlNotificatieId;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificatieStatusEnum status;

    @Column(nullable = false)
    private OffsetDateTime aangemaakt;

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        this.status = NotificatieStatusEnum.CREATED;
        this.aangemaakt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotifyNlNotificatieId() {
        return notifyNlNotificatieId;
    }

    public void setNotifyNlNotificatieId(UUID notifyNlNotificatieId) {
        this.notifyNlNotificatieId = notifyNlNotificatieId;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public NotificatieStatusEnum getStatus() {
        return status;
    }

    public void setStatus(NotificatieStatusEnum status) {
        this.status = status;
    }

    public OffsetDateTime getAangemaakt() {
        return aangemaakt;
    }
}
