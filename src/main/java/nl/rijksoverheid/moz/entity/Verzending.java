package nl.rijksoverheid.moz.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.common.NotificatieStatus;
import nl.rijksoverheid.moz.common.VerzendKanaal;
import nl.rijksoverheid.moz.common.VerzendingType;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
public class Verzending extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @JsonIgnore
    @ManyToOne(optional = false)
    @JoinColumn(name = "notificatie_id")
    @NotNull
    private Notificatie notificatie;

    @NotNull
    @Enumerated(EnumType.STRING)
    private VerzendingType type;

    @Nullable
    @Enumerated(EnumType.STRING)
    private VerzendKanaal kanaal;

    @NotNull
    @Enumerated(EnumType.STRING)
    private NotificatieStatus status;

    @Nullable
    private UUID notifyReferentie;

    @Nullable
    private String ontvangerEmail;

    @Nullable
    @Embedded
    private Adres ontvangerAdres;

    @Column(updatable = false)
    private Instant aangemaaktAt;

    @Nullable
    private Instant verzondenAt;

    @Nullable
    private Instant afgerondAt;

    @OneToMany(mappedBy = "verzending", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 32)
    private List<StatusGebeurtenis> statusGebeurtenissen = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        aangemaaktAt = Instant.now();
    }

    public Notificatie getNotificatie() {
        return notificatie;
    }

    public void setNotificatie(Notificatie notificatie) {
        this.notificatie = notificatie;
    }

    public VerzendingType getType() {
        return type;
    }

    public void setType(VerzendingType type) {
        this.type = type;
    }

    @Nullable
    public VerzendKanaal getKanaal() {
        return kanaal;
    }

    public void setKanaal(@Nullable VerzendKanaal kanaal) {
        this.kanaal = kanaal;
    }

    public NotificatieStatus getStatus() {
        return status;
    }

    public void setStatus(NotificatieStatus status) {
        this.status = status;
    }

    @Nullable
    public UUID getNotifyReferentie() {
        return notifyReferentie;
    }

    public void setNotifyReferentie(@Nullable UUID notifyReferentie) {
        this.notifyReferentie = notifyReferentie;
    }

    @Nullable
    public String getOntvangerEmail() {
        return ontvangerEmail;
    }

    public void setOntvangerEmail(@Nullable String ontvangerEmail) {
        this.ontvangerEmail = ontvangerEmail;
    }

    @Nullable
    public Adres getOntvangerAdres() {
        return ontvangerAdres;
    }

    public void setOntvangerAdres(@Nullable Adres ontvangerAdres) {
        this.ontvangerAdres = ontvangerAdres;
    }

    public Instant getAangemaaktAt() {
        return aangemaaktAt;
    }

    @Nullable
    public Instant getVerzondenAt() {
        return verzondenAt;
    }

    public void setVerzondenAt(@Nullable Instant verzondenAt) {
        this.verzondenAt = verzondenAt;
    }

    @Nullable
    public Instant getAfgerondAt() {
        return afgerondAt;
    }

    public void setAfgerondAt(@Nullable Instant afgerondAt) {
        this.afgerondAt = afgerondAt;
    }

    public List<StatusGebeurtenis> getStatusGebeurtenissen() {
        return Collections.unmodifiableList(statusGebeurtenissen);
    }

    public void addStatusGebeurtenis(StatusGebeurtenis statusGebeurtenis) {
        statusGebeurtenissen.add(statusGebeurtenis);
        statusGebeurtenis.setVerzending(this);
    }
}
