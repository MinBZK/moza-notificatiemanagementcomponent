package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

/**
 * Voert een StatusUpdateOpdracht uit zodra de transactie waarin hij is afgevuurd is gecommit.
 * <p>
 * AFTER_SUCCESS is hier de hele reden van bestaan: zou de statusupdate vóór de commit verstuurd
 * worden, dan kan de Dienstverlener een status krijgen die de NMC vervolgens terugrolt (de commit
 * kan alsnog falen op een OptimisticLockException door een gelijktijdige tweede delivery receipt,
 * of op een JTA-timeout). Faalt de transactie, dan wordt deze observer niet aangeroepen.
 * <p>
 * Bewust een eigen bean en geen observer-methode op ConsumentCallbackAdapter zelf: die adapter
 * wordt in tests met @InjectMock vervangen, en een observer-methode op een mock doet niets.
 */
@ApplicationScoped
public class StatusUpdateVerzender {

    private final ConsumentCallbackAdapter consumentCallbackAdapter;

    public StatusUpdateVerzender(ConsumentCallbackAdapter consumentCallbackAdapter) {
        this.consumentCallbackAdapter = consumentCallbackAdapter;
    }

    void verstuurNaCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) StatusUpdateOpdracht opdracht) {
        consumentCallbackAdapter.stuurStatusUpdate(opdracht);
    }
}
