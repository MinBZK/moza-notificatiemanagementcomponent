package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    // Toegekend bij het aanmaken en niet bij de persist: de versleutelde velden zijn aan deze id
    // gebonden en worden vóór de eerste opslag gezet.
    @Id
    private UUID id;

    @Version
    private long versie;

    @Column(name = "external_reference", unique = true)
    private UUID externalReference;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    // Kopie van status en registratietijd van het laatste geschiedenisrecord, zodat een retentiejob en
    // lijstvragen op notificatie filteren zonder over notificatie_status te aggregeren. Binnen Java
    // schrijft alleen registreerStatus(NotificatieStatus) ze; geen constraint koppelt laatste_status aan
    // het hoogste volgnummer, dus een tweede schrijver moet de notificatie-rij locken (SELECT ... FOR
    // UPDATE).
    @Enumerated(EnumType.STRING)
    @Column(name = "laatste_status", nullable = false, length = 32)
    private StatusWaarde laatsteStatus;

    @Column(name = "laatste_status_update", nullable = false)
    private OffsetDateTime laatsteStatusUpdate;

    // @OrderColumn en geen @OrderBy: zonder ordeningskolom is dit voor Hibernate een bag, en wordt
    // elke toevoeging een delete-all plus reinsert. De volgorde is daarmee registratievolgorde, niet
    // die van tijdstip — dat laatste zou een oude completed_at vóór de aanmaakstatus sorteren.
    @ElementCollection
    @CollectionTable(name = "notificatie_status", joinColumns = @JoinColumn(name = "notificatie_id"))
    @OrderColumn(name = "volgnummer")
    private List<NotificatieStatus> statusGeschiedenis = new ArrayList<>();

    @Column(name = "ontvanger_versleuteld")
    private byte[] ontvangerVersleuteld;

    @Column(name = "personalisation_versleuteld")
    private byte[] personalisationVersleuteld;

    @Column(name = "sleutel_gewrapt")
    private byte[] sleutelGewrapt;

    @Column(name = "kek_versie")
    private Integer kekVersie;

    protected Notificatie() {
        // Voor JPA
    }

    public Notificatie(String callbackUrl) {
        this.id = UUID.randomUUID();
        this.callbackUrl = callbackUrl;
        registreerStatus(NotificatieStatus.opEigenKlok(StatusWaarde.CREATED, OffsetDateTime.now(ZoneOffset.UTC)));
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

    /** Registratietijd van de huidige status, op de eigen klok. */
    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatusUpdate;
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
     * @throws IllegalStateException als er al een referentie gekoppeld is, of als de notificatie
     *         niet meer op {@code CREATED} staat
     */
    public void markeerVerzonden(UUID externalReference) {
        Objects.requireNonNull(externalReference, "externalReference is verplicht");

        if (this.externalReference != null) {
            throw new IllegalStateException("Notificatie " + id + " is al gekoppeld aan NotifyNL-referentie "
                    + this.externalReference);
        }

        if (!StatusWaarde.SENDING.volgtOp(laatsteStatus)) {
            throw new IllegalStateException("Notificatie " + id + " kan niet verzonden worden vanuit status "
                    + laatsteStatus);
        }

        this.externalReference = externalReference;
        registreerStatus(NotificatieStatus.opEigenKlok(StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Legt een terugmelding van NotifyNL vast als {@link StatusWaarde#volgtOp} hem als nieuw ziet.
     *
     * @param opgetreden gebeurtenistijd van de bron; bij null valt de NMC terug op de eigen klok
     * @return false als de melding niets nieuws is en dus niet is vastgelegd
     */
    public boolean verwerkTerugmelding(StatusWaarde status, OffsetDateTime opgetreden) {
        Objects.requireNonNull(status, "status is verplicht");

        if (!status.volgtOp(laatsteStatus)) {
            return false;
        }

        OffsetDateTime nu = OffsetDateTime.now(ZoneOffset.UTC);
        registreerStatus(new NotificatieStatus(status, opgetreden != null ? opgetreden : nu, nu));

        return true;
    }

    // Enige mutatiepunt voor status. Bewust geen controle op tijdstip: een scheve of oude
    // gebeurtenistijd weigeren zou een geschiedenis opleveren die laatsteStatus tegenspreekt.
    private void registreerStatus(NotificatieStatus record) {
        this.statusGeschiedenis.add(record);
        this.laatsteStatus = record.status();
        this.laatsteStatusUpdate = record.geregistreerd();
    }

    // Tijdstip van het eerste geschiedenisrecord: de CREATED uit de constructor, of voor een rij uit
    // de V2-backfill zijn oude aanmaaktijdstip. Vereist een actieve persistence context.
    public OffsetDateTime getAangemaakt() {
        return eersteStatus().tijdstip();
    }

    // Vereist een actieve persistence context: statusGeschiedenis is een lazy @ElementCollection.
    public List<NotificatieStatus> getStatusGeschiedenis() {
        bevestigVolledigeGeschiedenis();

        return List.copyOf(statusGeschiedenis);
    }

    // Vangnet voor rijen die buiten Java zijn ontstaan: de database eist geen statusregel, en een
    // ontbrekend volgnummer levert via @OrderColumn een null in de lijst op. Zonder deze controle
    // wordt dat een NoSuchElementException of NullPointerException die de oorzaak niet noemt.
    private void bevestigVolledigeGeschiedenis() {
        if (statusGeschiedenis.isEmpty()) {
            throw new IllegalStateException(
                    "Notificatie " + id + " heeft geen statusgeschiedenis, datamigratie onvolledig?");
        }

        if (statusGeschiedenis.contains(null)) {
            throw new IllegalStateException("Notificatie " + id + " mist een volgnummer in notificatie_status; "
                    + "de statusgeschiedenis is buiten Hibernate om gewijzigd");
        }
    }

    private NotificatieStatus eersteStatus() {
        bevestigVolledigeGeschiedenis();

        return statusGeschiedenis.getFirst();
    }

    public void bewaarVersleuteldeGegevens(VersleuteldeGegevens gegevens) {
        Objects.requireNonNull(gegevens, "gegevens is verplicht");
        this.ontvangerVersleuteld = gegevens.ontvangerVersleuteld();
        this.personalisationVersleuteld = gegevens.personalisationVersleuteld();
        this.sleutelGewrapt = gegevens.sleutelGewrapt();
        this.kekVersie = gegevens.kekVersie();
    }

    public VersleuteldeGegevens getVersleuteldeGegevens() {
        return new VersleuteldeGegevens(ontvangerVersleuteld, personalisationVersleuteld, sleutelGewrapt, kekVersie);
    }
}
