package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * De cursor tot waar de Dienstverlener de feed heeft verwerkt, door hem zelf teruggeschreven. Eén
 * per dienstverlener; hij gaat alleen vooruit.
 */
@Entity
public class Bevestiging {

    @Id
    @Column(name = "dv_id")
    private UUID dvId;

    @Column(nullable = false)
    private int epoch;

    @Type(Xid8Type.class)
    @Column(nullable = false)
    private Long xid;

    @Column(name = "event_id", nullable = false)
    private long eventId;

    @Column(name = "bevestigd_op", nullable = false)
    private OffsetDateTime bevestigdOp;

    protected Bevestiging() {
        // Voor JPA
    }

    public UUID getDvId() {
        return dvId;
    }

    public Cursor getCursor() {
        return new Cursor(epoch, xid, eventId);
    }

    public OffsetDateTime getBevestigdOp() {
        return bevestigdOp;
    }
}
