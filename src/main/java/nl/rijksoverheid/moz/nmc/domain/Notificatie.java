package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
public class Notificatie {

    @Id
    @GeneratedValue
    private UUID id;

    @Version
    private long versie;

    @Column(name = "external_reference", unique = true)
    private UUID externalReference;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    // Kopie van het laatste record in statusGeschiedenis, zodat de retentiejob op een geïndexeerde
    // kolom kan selecteren in plaats van per notificatie een MAX over notificatie_status te
    // berekenen. Hier stuurt de code op, niet op de geschiedenis.
    //
    // registreerStatus is binnen Java het enige pad naar beide velden, dus ze kunnen niet uiteen
    // lopen. In de database koppelt geen constraint laatste_status aan de rij met het hoogste
    // volgnummer, dus een schrijver die Hibernate omzeilt moet ze zelf in pas houden.
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "laatste_status", nullable = false, length = 32)),
            @AttributeOverride(name = "tijdstip", column = @Column(name = "laatste_status_tijdstip", nullable = false)),
            @AttributeOverride(name = "geregistreerd", column = @Column(name = "laatste_status_update", nullable = false))
    })
    private NotificatieStatus laatsteStatus;

    // @OrderColumn en geen @OrderBy: zonder ordeningskolom is dit voor Hibernate een bag, en wordt
    // elke toevoeging een delete-all plus reinsert. De volgorde is daarmee registratievolgorde, niet
    // die van tijdstip — dat laatste zou een oude completed_at vóór de aanmaakstatus sorteren.
    @ElementCollection
    @CollectionTable(name = "notificatie_status", joinColumns = @JoinColumn(name = "notificatie_id"))
    @OrderColumn(name = "volgnummer")
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

    /** De huidige status met zijn gebeurtenis- en registratietijd. */
    public NotificatieStatus getStatus() {
        return laatsteStatus;
    }

    public UUID getExternalReference() {
        return externalReference;
    }

    /**
     * Koppelt de notificatie aan de verzending bij NotifyNL en legt {@code SENDING} vast.
     * <p>
     * Die twee horen altijd samen: zonder referentie vindt {@code findByExternalReference} de
     * notificatie nooit meer, en zonder {@code SENDING} blijft hij op {@code CREATED} staan terwijl
     * de e-mail al weg is.
     *
     * @throws IllegalStateException als er al een referentie gekoppeld is; twee verzendingen onder
     *         één notificatie zou de tweede de eerste laten overschrijven, waarna de receipts van de
     *         eerste nergens meer thuishoren
     */
    public void markeerVerzonden(UUID externalReference) {
        Objects.requireNonNull(externalReference, "externalReference is verplicht");

        if (this.externalReference != null) {
            throw new IllegalStateException("Notificatie " + id + " is al gekoppeld aan NotifyNL-referentie "
                    + this.externalReference);
        }

        this.externalReference = externalReference;
        registreerStatus(StatusWaarde.SENDING);
    }

    /** Registreert een status die de NMC zelf vaststelt; gebeurtenis- en registratietijd vallen samen. */
    public void registreerStatus(StatusWaarde status) {
        registreerStatus(NotificatieStatus.opEigenKlok(status, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Registreert een status die elders is ontstaan, met de gebeurtenistijd van die bron — voor een
     * delivery receipt van NotifyNL hun completed_at/sent_at/created_at.
     */
    public void registreerStatus(StatusWaarde status, OffsetDateTime opgetreden) {
        registreerStatus(new NotificatieStatus(status, opgetreden, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    // Enige mutatiepunt voor status. Bewust geen controle op tijdstip hier: welke overgangen mogen
    // wordt bepaald door StatusWaarde#volgtOp in NotificatieService. Zou deze methode een registratie
    // alsnog weigeren te projecteren omdat de gebeurtenistijd ouder is, dan bevat de geschiedenis een
    // status die laatsteStatus tegenspreekt.
    private void registreerStatus(NotificatieStatus record) {
        this.statusGeschiedenis.add(record);
        this.laatsteStatus = record;
    }

    // Afgeleid van het eerste statusGeschiedenis-record. Voor notificaties die door deze code zijn
    // aangemaakt is dat de CREATED uit de constructor; voor rijen uit de V2-backfill is het hun
    // status van dat moment, want V1 legde geen geschiedenis vast. Vereist een actieve
    // persistence context.
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
