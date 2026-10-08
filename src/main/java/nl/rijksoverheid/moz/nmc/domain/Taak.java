package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Eén bijwerking die een worker moet uitvoeren, met {@code due} als timer. Een claim zet een lease en
 * verhoogt het claim-epoch; elke schrijfactie op de taak daarna toetst dat epoch, zodat een worker
 * die zijn lease verloor niets meer kan afronden of uitstellen.
 * <p>
 * De tabel is gepartitioneerd op {@code soort}; alle queries in {@code TaakRepository} noemen de soort,
 * zodat PostgreSQL naar één partitie kan. Wijzigingen lopen via die repository, niet via deze entity:
 * daarom zijn er geen setters.
 */
@Entity
public class Taak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private TaakSoort soort;

    // Leeg voor een taak per systeem, zoals onderhoud.
    @Column(name = "dv_id")
    private UUID dvId;

    // Leeg voor een taak per dienstverlener of per systeem.
    @Column(name = "notificatie_id")
    private UUID notificatieId;

    @Column(nullable = false)
    private OffsetDateTime due;

    @Column(name = "lease_tot")
    private OffsetDateTime leaseTot;

    @Column(name = "claim_epoch", nullable = false)
    private long claimEpoch;

    // Telt alleen mislukkingen in het NMC zelf; een uitstel na een fout bij een externe dienst niet.
    @Column(nullable = false)
    private int pogingen;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaakStatus status;

    // Geen contactgegevens, identificerende nummers of berichtinhoud; alleen verwijzingen.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private Map<String, String> payload;

    protected Taak() {
        // Voor JPA
    }

    public Taak(TaakSoort soort, UUID dvId, UUID notificatieId, OffsetDateTime due, String traceId,
                Map<String, String> payload) {
        this.soort = Objects.requireNonNull(soort, "soort is verplicht");
        this.dvId = dvId;
        this.notificatieId = notificatieId;
        this.due = Objects.requireNonNull(due, "due is verplicht").withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        this.traceId = traceId;
        this.payload = payload;
        this.status = TaakStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public TaakSoort getSoort() {
        return soort;
    }

    public UUID getDvId() {
        return dvId;
    }

    public UUID getNotificatieId() {
        return notificatieId;
    }

    public OffsetDateTime getDue() {
        return due;
    }

    public OffsetDateTime getLeaseTot() {
        return leaseTot;
    }

    public long getClaimEpoch() {
        return claimEpoch;
    }

    public int getPogingen() {
        return pogingen;
    }

    public String getTraceId() {
        return traceId;
    }

    public TaakStatus getStatus() {
        return status;
    }

    public Map<String, String> getPayload() {
        return payload == null ? Map.of() : Map.copyOf(payload);
    }
}
