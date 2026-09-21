package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;

/**
 * Voert een StatusUpdateOpdracht pas uit na een geslaagde commit, zodat de Dienstverlener nooit een
 * teruggerolde status krijgt.
 * <p>
 * Eigen bean en geen observer op ConsumentCallbackAdapter: die wordt in tests met @InjectMock
 * vervangen, en een observer op een mock doet niets.
 */
@ApplicationScoped
public class StatusUpdateVerzender {

    private final ConsumentCallbackAdapter consumentCallbackAdapter;

    public StatusUpdateVerzender(ConsumentCallbackAdapter consumentCallbackAdapter) {
        this.consumentCallbackAdapter = consumentCallbackAdapter;
    }

    void verstuurNaCommit(@Observes(during = TransactionPhase.AFTER_SUCCESS) StatusUpdateOpdracht opdracht) {
        try {
            consumentCallbackAdapter.stuurStatusUpdate(opdracht);
        } catch (RuntimeException e) {
            // Deze methode draait vanuit Synchronization#afterCompletion, waar de transactiemanager
            // elke Throwable zelf vangt en hooguit onder com.arjuna.* logt. Zonder deze catch ziet
            // wie op nl.rijksoverheid.moz.* filtert niets, terwijl een fout in de NMC zelf élke
            // statusupdate treft. Gooien heeft geen zin: de transactie is al gecommit.
            Log.errorf(e, "Statusupdate voor notificatie %s (status %s) kon niet verstuurd worden door "
                    + "een fout in de NMC zelf — dit treft waarschijnlijk alle consument-callbacks",
                    opdracht.notificatieId(), opdracht.status());
        }
    }
}
