package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging;
import nl.rijksoverheid.moz.nmc.domain.Bevestiging_;
import nl.rijksoverheid.moz.nmc.domain.Cursor;

import java.time.OffsetDateTime;
import java.util.UUID;

@ApplicationScoped
public class BevestigingRepository implements PanacheRepositoryBase<Bevestiging, UUID> {

    /**
     * Schrijft de cursor als hij minstens even ver ligt als de bewaarde, zodat een herhaalde bevestiging
     * het tijdstip ververst; false als de bewaarde verder was.
     * Eén statement, zodat twee gelijktijdige bevestigingen elkaar niet terugzetten.
     */
    public boolean updateCursorPositie(UUID dvId, Cursor cursor, OffsetDateTime bevestigdOp) {
        String positie = "(" + Bevestiging_.EPOCH + ", " + Bevestiging_.TRANSACTIE_ID + ", " + Bevestiging_.EVENT_ID + ")";

        return getSession().createMutationQuery("INSERT INTO Bevestiging (" + Bevestiging_.DV_ID + ", "
                        + Bevestiging_.EPOCH + ", " + Bevestiging_.TRANSACTIE_ID + ", " + Bevestiging_.EVENT_ID + ", "
                        + Bevestiging_.BEVESTIGD_OP + ") VALUES (:dvId, :epoch, :transactieId, :eventId, :bevestigdOp)"
                        + " ON CONFLICT(" + Bevestiging_.DV_ID + ") DO UPDATE SET "
                        + Bevestiging_.EPOCH + " = excluded." + Bevestiging_.EPOCH + ", "
                        + Bevestiging_.TRANSACTIE_ID + " = excluded." + Bevestiging_.TRANSACTIE_ID + ", "
                        + Bevestiging_.EVENT_ID + " = excluded." + Bevestiging_.EVENT_ID + ", "
                        + Bevestiging_.BEVESTIGD_OP + " = excluded." + Bevestiging_.BEVESTIGD_OP
                        + " WHERE " + positie + " <= (excluded." + Bevestiging_.EPOCH + ", excluded."
                        + Bevestiging_.TRANSACTIE_ID + ", excluded." + Bevestiging_.EVENT_ID + ")")
                .setParameter("dvId", dvId)
                .setParameter("epoch", cursor.epoch())
                .setParameter("transactieId", cursor.transactieId(), Bevestiging_.transactieId.getType())
                .setParameter("eventId", cursor.eventId())
                .setParameter("bevestigdOp", bevestigdOp)
                .executeUpdate() == 1;
    }
}
