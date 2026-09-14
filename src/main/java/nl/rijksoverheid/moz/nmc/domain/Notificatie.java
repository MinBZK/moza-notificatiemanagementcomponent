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
    // kolommen staan op notificatie zodat de retentiejob op één geïndexeerde kolom kan
    // selecteren in plaats van per notificatie een MAX over notificatie_status te berekenen.
    // NotificatieTest en NotificatiePersistentieTest bewaken dat ze de geschiedenis blijven volgen.
    @Enumerated(EnumType.STRING)
    @Column(name = "laatste_status", nullable = false, length = 32)
    private StatusWaarde laatsteStatus;

    // De gebeurtenistijd van laatsteStatus (NotificatieStatus#tijdstip), op de klok van de bron die
    // de status meldde. Voor het afleverbewijs; nergens om op te selecteren of te sturen, want een
    // externe klok kan scheef of oud zijn.
    @Column(name = "laatste_status_tijdstip", nullable = false)
    private OffsetDateTime laatsteStatusTijdstip;

    // De registratietijd van de laatste statusregistratie, op de eigen klok van de NMC
    // (NotificatieStatus#geregistreerd). De retentiejob selecteert hierop. Bewust niet de
    // gebeurtenistijd: een receipt met een scheve of oude completed_at zou een notificatie anders
    // meteen opruimbaar maken, en een notificatie waar zojuist nog iets over binnenkwam is niet
    // inactief. Loopt daarom altijd vooruit, ook bij een terug gedateerde registratie.
    @Column(name = "laatste_status_update", nullable = false)
    private OffsetDateTime laatsteStatusUpdate;

    // Geordend op geregistreerd (de eigen klok), niet op tijdstip (de klok van de bron). Die eigen
    // klok is monotoon, dus de geschiedenis staat altijd in de volgorde waarin de NMC de statussen
    // vastlegde en getFirst() is altijd de CREATED uit de constructor. Ordenen op tijdstip zou een
    // receipt met een scheve of oude completed_at vóór de aanmaak laten sorteren, waarna
    // getAangemaakt() de verkeerde rij teruggeeft.
    @ElementCollection
    @CollectionTable(name = "notificatie_status", joinColumns = @JoinColumn(name = "notificatie_id"))
    @OrderBy("geregistreerd ASC")
    private List<NotificatieStatus> statusGeschiedenis = new ArrayList<>();

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.callbackUrl = callbackUrl;
        registreerStatus(StatusWaarde.CREATED);
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

    /** Registratietijd van de laatste statusregistratie; waar de bewaartermijn op vaart. */
    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatusUpdate;
    }

    /** Gebeurtenistijd van de huidige status, op de klok van de bron die hem meldde. */
    public OffsetDateTime getLaatsteStatusTijdstip() {
        return laatsteStatusTijdstip;
    }

    public UUID getExternalReference() {
        return externalReference;
    }

    public void setExternalReference(UUID externalReference) {
        this.externalReference = externalReference;
    }

    /** Registreert een status die de NMC zelf vaststelt; gebeurtenis- en registratietijd vallen samen. */
    public void registreerStatus(StatusWaarde status) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        registreerStatus(NotificatieStatus.opEigenKlok(status, nu), nu);
    }

    /**
     * Registreert een status die elders is ontstaan, met de gebeurtenistijd van die bron — voor een
     * delivery receipt van NotifyNL hun completed_at/sent_at/created_at.
     */
    public void registreerStatus(StatusWaarde status, OffsetDateTime opgetreden) {
        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        registreerStatus(new NotificatieStatus(status, opgetreden, nu), nu);
    }

    // Enige mutatiepunt voor status: legt het record in de geschiedenis vast en werkt de projectie
    // bij. De projectie volgt altijd het laatst geregistreerde record, zonder de gebeurtenistijd
    // ertegen af te wegen.
    //
    // Bewust géén tweede controle op tijdstip hier. Welke overgangen zijn toegestaan wordt bepaald
    // door de rangorde in StatusWaarde#volgtOp, die NotificatieService toepast vóór hij hierheen
    // gaat. Zou deze methode een registratie alsnog weigeren te projecteren omdat de gebeurtenistijd
    // ouder is, dan zou de geschiedenis DELIVERED bevatten terwijl laatsteStatus op SENDING blijft
    // staan: twee bronnen die elkaar tegenspreken. De gebeurtenistijd komt van een externe klok en
    // is daarmee ongeschikt om over correctheid te beslissen; de rangorde niet.
    private void registreerStatus(NotificatieStatus record, OffsetDateTime nu) {
        this.statusGeschiedenis.add(record);
        this.laatsteStatus = record.status();
        this.laatsteStatusTijdstip = record.tijdstip();
        this.laatsteStatusUpdate = nu;
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
