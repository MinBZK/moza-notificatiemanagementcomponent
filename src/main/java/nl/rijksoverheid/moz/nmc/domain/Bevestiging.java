package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * De bevestigde leespositie van één Dienstverlener in de eventfeed. De Dienstverlener leest de feed
 * en meldt daarna zelf tot en met welke cursor hij de events heeft verwerkt; die cursor staat hier,
 * met het tijdstip van de melding. Er is één rij per dienstverlener en de positie gaat alleen
 * vooruit. Het eventlog bewaart de events vanaf een bevestigde positie zolang die jong genoeg is.
 */
@Entity
public class Bevestiging {

    @Id
    @Column(name = "dv_id")
    private UUID dvId;

    @Column(nullable = false)
    private int epoch;

    @Type(TransactieIdType.class)
    @Column(name = "transactie_id", nullable = false)
    private Long transactieId;

    @Column(name = "event_id", nullable = false)
    private long eventId;

    @Column(name = "bevestigd_op", nullable = false)
    private OffsetDateTime bevestigdOp;

    protected Bevestiging() {
        // Voor JPA
    }

    public Cursor getCursor() {
        return new Cursor(epoch, transactieId, eventId);
    }

    public OffsetDateTime getBevestigdOp() {
        return bevestigdOp;
    }
}
