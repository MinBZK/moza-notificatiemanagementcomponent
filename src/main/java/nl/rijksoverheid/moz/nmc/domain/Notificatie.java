package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StatusWaarde status;

    @Column(nullable = false)
    private OffsetDateTime aangemaakt;

    @Column(name = "laatste_status_update", nullable = false)
    private OffsetDateTime laatsteStatusUpdate;

    // @OrderColumn: anders is dit een ongeordende JPA-bag (geen garantie op volgorde bij herladen,
    // en elke wijziging herschrijft de hele collectie i.p.v. één rij toe te voegen).
    @ElementCollection
    @CollectionTable(name = "notificatie_status", joinColumns = @JoinColumn(name = "notificatie_id"))
    @OrderColumn(name = "volgnummer")
    private List<NotificatieStatus> statusGeschiedenis = new ArrayList<>();

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        this.aangemaakt = nu;
        registreerStatus(StatusWaarde.CREATED, nu);
    }

    public UUID getId() {
        return id;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public StatusWaarde getStatus() {
        return status;
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

    // Enige mutatiepunt voor status: houdt status, laatsteStatusUpdate en statusGeschiedenis in
    // sync. De geschiedenis-toevoeging staat eerst omdat dat de enige stap is die kan falen
    // (@OrderColumn initialiseert de lazy collectie, wat op een detached entiteit een
    // LazyInitializationException geeft) — zo blijft een fout hier nooit half toegepast.
    private void registreerStatus(StatusWaarde status, OffsetDateTime tijdstip) {
        this.statusGeschiedenis.add(new NotificatieStatus(status, tijdstip));
        this.status = status;
        this.laatsteStatusUpdate = tijdstip;
    }

    public OffsetDateTime getAangemaakt() {
        return aangemaakt;
    }

    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatusUpdate;
    }

    public List<NotificatieStatus> getStatusGeschiedenis() {
        return List.copyOf(statusGeschiedenis);
    }
}
