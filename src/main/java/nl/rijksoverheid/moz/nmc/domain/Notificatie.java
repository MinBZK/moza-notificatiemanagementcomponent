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
    // NotifyNL een 5xx krijgt en dezelfde callback opnieuw aanbiedt, dan zonder concurrentie.
    @Version
    private long versie;

    @Column(name = "external_reference", unique = true)
    private UUID externalReference;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    // Projectie van het chronologisch laatste record in statusGeschiedenis, bijgewerkt door
    // registreerStatus (het enige mutatiepunt). De geschiedenis blijft de bron van waarheid; deze
    // twee kolommen staan op notificatie zodat de retentiejob op één geïndexeerde kolom kan
    // selecteren in plaats van per notificatie een MAX over notificatie_status te berekenen.
    // NotificatieTest en NotificatiePersistentieTest bewaken dat ze de geschiedenis blijven volgen.
    @Enumerated(EnumType.STRING)
    @Column(name = "laatste_status", nullable = false, length = 32)
    private StatusWaarde laatsteStatus;

    @Column(name = "laatste_status_update", nullable = false)
    private OffsetDateTime laatsteStatusUpdate;

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

    public StatusWaarde getStatus() {
        return laatsteStatus;
    }

    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatusUpdate;
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

    // Enige mutatiepunt voor status: legt het record in de geschiedenis vast en werkt de projectie
    // bij. De projectie volgt alleen een record dat ook chronologisch het laatste is: @OrderBy
    // ordent op tijdstip, niet op registratievolgorde, dus een terug gedateerde registratie mag
    // laatsteStatus/laatsteStatusUpdate niet terugzetten.
    private void registreerStatus(StatusWaarde status, OffsetDateTime tijdstip) {
        this.statusGeschiedenis.add(new NotificatieStatus(status, tijdstip));

        if (laatsteStatusUpdate == null || !tijdstip.isBefore(laatsteStatusUpdate)) {
            this.laatsteStatus = status;
            this.laatsteStatusUpdate = tijdstip;
        }
    }

    // Afgeleid van het eerste statusGeschiedenis-record (altijd CREATED, zie de constructor),
    // vereist een actieve persistence context.
    public OffsetDateTime getAangemaakt() {
        return eersteStatus().tijdstip();
    }

    // Vereist een actieve persistence context: statusGeschiedenis is een lazy @ElementCollection.
    public List<NotificatieStatus> getStatusGeschiedenis() {
        return List.copyOf(statusGeschiedenis);
    }

    // Een Notificatie zonder statusgeschiedenis kan langs deze code niet ontstaan: de constructor
    // legt altijd CREATED vast. Blijft toch als vangnet staan omdat niets buiten Java dat afdwingt
    // (de tabel heeft geen constraint die minstens één statusregel eist), en een lege lijst hier
    // anders een NoSuchElementException oplevert die niets over de oorzaak zegt.
    private NotificatieStatus eersteStatus() {
        if (statusGeschiedenis.isEmpty()) {
            throw new IllegalStateException(
                    "Notificatie " + id + " heeft geen statusgeschiedenis, datamigratie onvolledig?");
        }

        return statusGeschiedenis.getFirst();
    }
}
