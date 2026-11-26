package nl.rijksoverheid.moz.nmc.config;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Controleert bij het opstarten de regels die over meer dan één property gaan. Een geschonden regel
 * laat de applicatie niet starten.
 */
@Startup
@ApplicationScoped
public class ConfigValidatie {

    public ConfigValidatie(
            TaakConfig taak,
            WebhookConfig webhook,
            VerzendbudgetConfig verzendbudget,
            WissenConfig wissen,
            AfleverbewijsConfig afleverbewijs) {
        // Een aanroep die langer duurt dan de lease, laat een tweede worker dezelfde bundel nog eens afleveren.
        if (webhook.timeout().compareTo(taak.lease()) >= 0) {
            throw new IllegalStateException("nmc.webhook.timeout moet korter dan nmc.taak.lease (" + taak.lease()
                    + ") zijn, is " + webhook.timeout());
        }

        if ((long) verzendbudget.verzendReservering() + verzendbudget.navraagReservering()
                > verzendbudget.tokensPerMinuut()) {
            throw new IllegalStateException(
                    "nmc.verzendbudget.verzend-reservering en nmc.verzendbudget.navraag-reservering"
                            + " zijn samen meer dan nmc.verzendbudget.tokens-per-minuut: "
                            + verzendbudget.verzendReservering()
                            + " + " + verzendbudget.navraagReservering() + " > " + verzendbudget.tokensPerMinuut());
        }

        // De rij verdwijnt met de versleutelde gegevens; wissen moet dus eerder of tegelijk.
        if (afleverbewijs.bewaartermijn().compareTo(wissen.termijn()) < 0) {
            throw new IllegalStateException("nmc.afleverbewijs.bewaartermijn (" + afleverbewijs.bewaartermijn()
                    + ") mag niet korter zijn dan nmc.wissen.termijn (" + wissen.termijn() + ")");
        }
    }
}
