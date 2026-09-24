package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Eén statusovergang van een notificatie in het eventlog. Het volgnummer is de versie van de
 * notificatie na de overgang. Bevat geen contactgegevens, identificerende nummers of referentie van
 * de Dienstverlener.
 * <p>
 * De kolom {@code xid} (transactie-id, gevuld door de database) is hier niet gemapt; de feed leest
 * hem met SQL.
 */
@Entity
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notificatie_id", nullable = false)
    private UUID notificatieId;

    @Column(nullable = false)
    private long volgnummer;

    // Leeg bij het eerste event van een notificatie.
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private NotificatieStatus van;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificatieStatus naar;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Reden reden;

    // Registratietijd op de klok van het NMC; het tijdstip uit een receipt staat op de poging.
    @Column(nullable = false)
    private OffsetDateTime tijdstip;

    protected Event() {
        // Voor JPA
    }

    public Event(UUID notificatieId, long volgnummer, NotificatieStatus van, NotificatieStatus naar, Reden reden) {
        this.notificatieId = Objects.requireNonNull(notificatieId, "notificatieId is verplicht");
        this.volgnummer = volgnummer;
        this.van = van;
        this.naar = Objects.requireNonNull(naar, "naar is verplicht");
        this.reden = reden;
        this.tijdstip = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public UUID getNotificatieId() {
        return notificatieId;
    }

    public long getVolgnummer() {
        return volgnummer;
    }

    public NotificatieStatus getVan() {
        return van;
    }

    public NotificatieStatus getNaar() {
        return naar;
    }

    public Reden getReden() {
        return reden;
    }

    public OffsetDateTime getTijdstip() {
        return tijdstip;
    }
}
