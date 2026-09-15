package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
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
        try {
            consumentCallbackAdapter.stuurStatusUpdate(opdracht);
        } catch (RuntimeException e) {
            // ConsumentCallbackAdapter laat bewust elke fout ontsnappen die niet aan de
            // Dienstverlener ligt: een kapotte truststore, een verkeerd geconfigureerde proxy, een
            // ontbrekende MessageBodyWriter. Vóór de verplaatsing naar deze observer had dat een
            // ontvanger — de aanroeper in NotificatieService, en daarboven de controller. Nu niet
            // meer: deze methode draait vanuit Synchronization#afterCompletion, waar de
            // transactiemanager elke Throwable zelf vangt en hooguit onder com.arjuna.* logt. Wie
            // op nl.rijksoverheid.moz.* filtert ziet dan niets, terwijl zo'n fout élke statusupdate
            // naar élke Dienstverlener treft. Vandaar hier een ERROR in het eigen namespace; gooien
            // heeft geen zin, de transactie is al gecommit.
            Log.errorf(e, "Statusupdate voor notificatie %s (status %s) kon niet verstuurd worden door "
                    + "een fout in de NMC zelf — dit treft waarschijnlijk alle consument-callbacks",
                    opdracht.notificatieId(), opdracht.status());
        }
    }
}
