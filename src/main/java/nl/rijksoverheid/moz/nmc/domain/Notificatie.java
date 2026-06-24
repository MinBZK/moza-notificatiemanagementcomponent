package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import nl.rijksoverheid.moz.nmc.common.NotificatieStatusEnum;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
public class Notificatie {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(unique = true)
    public UUID notifyNlNotificatieId;

    @Column
    public String callbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public NotificatieStatusEnum status;

    @Column(nullable = false)
    public OffsetDateTime aangemaakt;
}
