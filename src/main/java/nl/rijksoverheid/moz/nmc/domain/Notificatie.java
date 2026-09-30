package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

// De queries staan als @NamedQuery en niet in de repository, omdat Hibernate ze dan bij het opstarten
// controleert in plaats van bij de eerste aanroep — die valt voor de retentiejob midden in de nacht.
// JPA kent geen andere plek dan de entiteit; uitvoeren gebeurt uitsluitend in NotificatieRepository.
@NamedQuery(name = Notificatie.ZOEK_KANDIDATEN,
        query = "SELECT new nl.rijksoverheid.moz.nmc.repository.Kandidaat(n.id, n.status, n.laatsteStatusUpdate) "
                + "FROM Notificatie n WHERE n.id IN :ids ORDER BY n.laatsteStatusUpdate")
@NamedQuery(name = Notificatie.VERWIJDER_OP_ID, query = "DELETE FROM Notificatie n WHERE n.id IN :ids")
@Entity
public class Notificatie {

    public static final String ZOEK_KANDIDATEN = "Notificatie.zoekKandidaten";
    public static final String VERWIJDER_OP_ID = "Notificatie.verwijderOpId";

    // Toegekend bij het aanmaken en niet bij de persist: de versleutelde velden zijn aan deze id
    // gebonden en worden vóór de eerste opslag gezet.
    @Id
    private UUID id;

    // Loopt per overgang met één op en is het volgnummer van het bijbehorende event; een
    // databasetrigger weigert een nieuwe versie zonder event.
    @Version
    private long versie;

    // De dienstverlener namens wie de notificatie is aangenomen; elke lezing blijft binnen die grens.
    @Column(name = "dv_id", nullable = false, updatable = false)
    private UUID dvId;

    @Column(name = "callback_url", length = 2048)
    private String callbackUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificatieStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Reden reden;

    // Tijdstip van de laatste statuswijziging op de eigen klok.
    @Column(name = "laatste_status_update", nullable = false)
    private OffsetDateTime laatsteStatusUpdate;

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

    /** Een nieuwe notificatie, nog zonder status; {@code Overgangsfunctie#neemAan} slaat hem op. */
    public Notificatie(UUID dvId, String callbackUrl) {
        this.id = UUID.randomUUID();
        this.dvId = Objects.requireNonNull(dvId, "dvId is verplicht");
        this.callbackUrl = callbackUrl;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDvId() {
        return dvId;
    }

    public long getVersie() {
        return versie;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public NotificatieStatus getStatus() {
        return status;
    }

    public Reden getReden() {
        return reden;
    }

    public OffsetDateTime getLaatsteStatusUpdate() {
        return laatsteStatusUpdate;
    }

    /**
     * Zet status en reden. Alleen voor Overgangsfunctie, die de rij vergrendelt, de overgang toetst en
     * het event schrijft.
     */
    public void pasOvergangToe(NotificatieStatus naar, Reden reden) {
        this.status = Objects.requireNonNull(naar, "naar is verplicht");
        this.reden = reden;
        this.laatsteStatusUpdate = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
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
