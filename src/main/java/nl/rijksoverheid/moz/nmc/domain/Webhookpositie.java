package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Tot waar de terugkoppeltaak de events van een dienstverlener aan zijn webhook heeft geleverd, met
 * de mislukkingen sinds de laatste geslaagde levering. Geen bevestiging: die schrijft de
 * Dienstverlener zelf met de feedcursor.
 */
@Entity
public class Webhookpositie {

    @Id
    @Column(name = "dv_id")
    private UUID dvId;

    // Leeg (epoch, transactie-id, event-id): nog niets geleverd.
    private Integer epoch;

    @Type(TransactieIdType.class)
    @Column(name = "transactie_id")
    private Long transactieId;

    @Column(name = "event_id")
    private Long eventId;

    @Column(nullable = false)
    private int mislukkingen;

    @Column(name = "gepauzeerd_tot")
    private OffsetDateTime gepauzeerdTot;

    @Column(name = "bijgewerkt_op", nullable = false)
    private OffsetDateTime bijgewerktOp;

    protected Webhookpositie() {
        // Voor JPA
    }

    /**
     * @param positie       het laatst geleverde event; null als er nog niets is geleverd
     * @param mislukkingen  het aantal mislukte leveringen op rij
     * @param gepauzeerdTot tot wanneer de webhook gepauzeerd is; null als hij niet gepauzeerd is
     */
    public Webhookpositie(UUID dvId, Cursor positie, int mislukkingen, OffsetDateTime gepauzeerdTot) {
        this.dvId = dvId;
        this.epoch = positie == null ? null : positie.epoch();
        this.transactieId = positie == null ? null : positie.transactieId();
        this.eventId = positie == null ? null : positie.eventId();
        this.mislukkingen = mislukkingen;
        this.gepauzeerdTot = gepauzeerdTot;
    }

    /** Een dienstverlener waaraan nog nooit geleverd is. */
    public static Webhookpositie nieuw(UUID dvId) {
        return new Webhookpositie(dvId, null, 0, null);
    }

    public UUID getDvId() {
        return dvId;
    }

    /** Het laatst geleverde event; null als er nog niets is geleverd. */
    public Cursor getPositie() {
        return epoch == null ? null : new Cursor(epoch, transactieId, eventId);
    }

    public Optional<Cursor> laatstGeleverd() {
        return Optional.ofNullable(getPositie());
    }

    public int getMislukkingen() {
        return mislukkingen;
    }

    public OffsetDateTime getGepauzeerdTot() {
        return gepauzeerdTot;
    }
}
