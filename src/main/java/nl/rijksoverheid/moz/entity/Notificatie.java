package nl.rijksoverheid.moz.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotNull;
import nl.rijksoverheid.moz.common.IdentificatieType;
import nl.rijksoverheid.moz.common.ProfielType;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
public class Notificatie extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    private ProfielType profielType;

    @NotNull
    @Enumerated(EnumType.STRING)
    private IdentificatieType identificatieType;

    @NotNull
    private String identificatieNummer;

    @NotNull
    private String dienstverlener;

    @NotNull
    private String dienst;

    @NotNull
    private String berichtType;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String berichtgegevens;

    @Nullable
    private String referentie;

    @Column(updatable = false)
    private Instant aangemaaktAt;

    @OneToMany(mappedBy = "notificatie", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 32)
    private List<Verzending> verzendingen = new ArrayList<>();

    @PrePersist
    private void onCreate() {
        aangemaaktAt = Instant.now();
    }

    public ProfielType getProfielType() {
        return profielType;
    }

    public void setProfielType(ProfielType profielType) {
        this.profielType = profielType;
    }

    public IdentificatieType getIdentificatieType() {
        return identificatieType;
    }

    public void setIdentificatieType(IdentificatieType identificatieType) {
        this.identificatieType = identificatieType;
    }

    public String getIdentificatieNummer() {
        return identificatieNummer;
    }

    public void setIdentificatieNummer(String identificatieNummer) {
        this.identificatieNummer = identificatieNummer;
    }

    public String getDienstverlener() {
        return dienstverlener;
    }

    public void setDienstverlener(String dienstverlener) {
        this.dienstverlener = dienstverlener;
    }

    public String getDienst() {
        return dienst;
    }

    public void setDienst(String dienst) {
        this.dienst = dienst;
    }

    public String getBerichtType() {
        return berichtType;
    }

    public void setBerichtType(String berichtType) {
        this.berichtType = berichtType;
    }

    @Nullable
    public String getBerichtgegevens() {
        return berichtgegevens;
    }

    public void setBerichtgegevens(@Nullable String berichtgegevens) {
        this.berichtgegevens = berichtgegevens;
    }

    @Nullable
    public String getReferentie() {
        return referentie;
    }

    public void setReferentie(@Nullable String referentie) {
        this.referentie = referentie;
    }

    public Instant getAangemaaktAt() {
        return aangemaaktAt;
    }

    public List<Verzending> getVerzendingen() {
        return Collections.unmodifiableList(verzendingen);
    }

    public void addVerzending(Verzending verzending) {
        verzendingen.add(verzending);
        verzending.setNotificatie(this);
    }
}
