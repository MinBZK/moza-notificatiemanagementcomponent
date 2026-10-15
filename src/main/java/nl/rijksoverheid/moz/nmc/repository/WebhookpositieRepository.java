package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie_;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** De leverpositie van de webhook per dienstverlener. */
@ApplicationScoped
public class WebhookpositieRepository implements PanacheRepositoryBase<Webhookpositie, UUID> {

    /** De bewaarde positie, of een nieuwe als er voor deze dienstverlener nog geen rij is. */
    public Webhookpositie zoek(UUID dvId) {
        return findByIdOptional(dvId).orElseGet(() -> Webhookpositie.nieuw(dvId));
    }

    public void bewaar(Webhookpositie positie, OffsetDateTime nu) {
        Optional<Cursor> cursor = positie.laatstGeleverd();
        // Hibernate leidt de parametertypen in een insert niet af uit de kolommen; met de types van de
        // attributen komen een lege positie of pauze als null van het juiste type aan. geleverdOp verschuift
        // alleen als de positie verschuift: de leeftijd van de leverpositie, waarop het opruimen van het
        // eventlog let, telt vanaf de laatste levering en niet vanaf een mislukking.
        getSession().createMutationQuery("insert into Webhookpositie w (" + Webhookpositie_.DV_ID + ", "
                        + Webhookpositie_.EPOCH + ", " + Webhookpositie_.TRANSACTIE_ID + ", " + Webhookpositie_.EVENT_ID + ", "
                        + Webhookpositie_.MISLUKKINGEN + ", " + Webhookpositie_.GEPAUZEERD_TOT + ", "
                        + Webhookpositie_.BIJGEWERKT_OP + ", " + Webhookpositie_.GELEVERD_OP + ") values (:dvId, :epoch,"
                        + " :transactieId, :eventId, :mislukkingen, :gepauzeerdTot, :bijgewerktOp, :geleverdOp) on conflict("
                        + Webhookpositie_.DV_ID + ") do update set "
                        + Webhookpositie_.EPOCH + " = excluded." + Webhookpositie_.EPOCH + ", "
                        + Webhookpositie_.TRANSACTIE_ID + " = excluded." + Webhookpositie_.TRANSACTIE_ID + ", "
                        + Webhookpositie_.EVENT_ID + " = excluded." + Webhookpositie_.EVENT_ID + ", "
                        + Webhookpositie_.MISLUKKINGEN + " = excluded." + Webhookpositie_.MISLUKKINGEN + ", "
                        + Webhookpositie_.GEPAUZEERD_TOT + " = excluded." + Webhookpositie_.GEPAUZEERD_TOT + ", "
                        + Webhookpositie_.BIJGEWERKT_OP + " = excluded." + Webhookpositie_.BIJGEWERKT_OP + ", "
                        + Webhookpositie_.GELEVERD_OP + " = case when w." + Webhookpositie_.EPOCH + " = excluded."
                        + Webhookpositie_.EPOCH + " and w." + Webhookpositie_.TRANSACTIE_ID + " = excluded."
                        + Webhookpositie_.TRANSACTIE_ID + " and w." + Webhookpositie_.EVENT_ID + " = excluded."
                        + Webhookpositie_.EVENT_ID + " then w." + Webhookpositie_.GELEVERD_OP + " else excluded."
                        + Webhookpositie_.GELEVERD_OP + " end")
                .setParameter("dvId", positie.getDvId())
                .setParameter("epoch", cursor.map(Cursor::epoch).orElse(null), Webhookpositie_.epoch.getType())
                .setParameter("transactieId", cursor.map(Cursor::transactieId).orElse(null),
                        Webhookpositie_.transactieId.getType())
                .setParameter("eventId", cursor.map(Cursor::eventId).orElse(null), Webhookpositie_.eventId.getType())
                .setParameter("mislukkingen", positie.getMislukkingen())
                .setParameter("gepauzeerdTot", positie.getGepauzeerdTot(), Webhookpositie_.gepauzeerdTot.getType())
                .setParameter("bijgewerktOp", nu)
                .setParameter("geleverdOp", cursor.isPresent() ? nu : null, Webhookpositie_.geleverdOp.getType())
                .executeUpdate();
    }
}
