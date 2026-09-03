package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;

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

    // statusGeschiedenis is een Hibernate-bag (List + @OrderBy, geen @OrderColumn): elke mutatie
    // wordt uitgevoerd als "verwijder alle rijen van deze notificatie en voeg de hele lijst opnieuw
    // toe". Zonder versiecontrole overschrijft van twee gelijktijdige NotifyNL-callbacks de
    // laatste commit de statusregel van de eerste zonder enig signaal. Met @Version faalt die
    // tweede commit op een OptimisticLockException; die ontsnapt uit verwerkAfleverstatus, zodat
    // NotifyNL een 5xx krijgt en dezelfde callback opnieuw aanbiedt — dan zonder concurrentie.
    @Version
    private long versie;

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
        return eersteStatus().tijdstip();
    }

    // Afgeleid van het laatste statusGeschiedenis-record — vereist een actieve persistence context.
    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatus().tijdstip();
    }

    // Vereist een actieve persistence context: statusGeschiedenis is een lazy @ElementCollection.
    public List<NotificatieStatus> getStatusGeschiedenis() {
        return List.copyOf(statusGeschiedenis);
    }

    private NotificatieStatus eersteStatus() {
        controleerGeschiedenisGevuld();

        return statusGeschiedenis.getFirst();
    }

    private NotificatieStatus laatsteStatus() {
        controleerGeschiedenisGevuld();

        return statusGeschiedenis.getLast();
    }

    // Een Notificatie zonder statusgeschiedenis kan langs deze code niet ontstaan: de constructor
    // legt altijd CREATED vast. Blijft toch als vangnet staan omdat niets buiten Java dat afdwingt
    // (de tabel heeft geen constraint die minstens één statusregel eist), en een lege lijst hier
    // anders een IndexOutOfBoundsException oplevert die niets over de oorzaak zegt.
    private void controleerGeschiedenisGevuld() {
        if (statusGeschiedenis.isEmpty()) {
            throw new IllegalStateException(
                    "Notificatie " + id + " heeft geen statusgeschiedenis — datamigratie onvolledig?");
        }
    }
}
