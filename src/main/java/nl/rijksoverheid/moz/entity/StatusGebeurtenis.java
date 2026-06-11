package nl.rijksoverheid.moz.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.common.NotificatieStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
public class StatusGebeurtenis extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "verzending_id")
    @NotNull
    private Verzending verzending;

    @NotNull
    @Enumerated(EnumType.STRING)
    private NotificatieStatus status;

    @Nullable
    private String omschrijving;

    @Column(updatable = false)
    private Instant tijdstip;

    @PrePersist
    private void onCreate() {
        tijdstip = Instant.now();
    }

    public Verzending getVerzending() {
        return verzending;
    }

    public void setVerzending(Verzending verzending) {
        this.verzending = verzending;
    }

    public NotificatieStatus getStatus() {
        return status;
    }

    public void setStatus(NotificatieStatus status) {
        this.status = status;
    }

    @Nullable
    public String getOmschrijving() {
        return omschrijving;
    }

    public void setOmschrijving(@Nullable String omschrijving) {
        this.omschrijving = omschrijving;
    }

    public Instant getTijdstip() {
        return tijdstip;
    }
}
