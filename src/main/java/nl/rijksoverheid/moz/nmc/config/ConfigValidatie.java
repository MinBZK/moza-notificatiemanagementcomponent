package nl.rijksoverheid.moz.nmc.config;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * Controleert bij het opstarten de regels die over meer dan één property gaan. Een geschonden regel
 * laat de applicatie niet starten.
 */
@Startup
@ApplicationScoped
public class ConfigValidatie {

    // Een test of een ontwikkelaar haalt de JWKS zonder TLS van de eigen machine.
    private static final Set<String> LOKAAL = Set.of("localhost", "127.0.0.1");

    public ConfigValidatie(TaakConfig taak, WebhookConfig webhook, VerzendbudgetConfig verzendbudget,
                           WissenConfig wissen, AfleverbewijsConfig afleverbewijs, FscConfig fsc) {
        // Een aanroep die langer duurt dan de lease, laat een tweede worker dezelfde bundel nog eens afleveren.
        if (webhook.timeout().compareTo(taak.lease()) >= 0) {
            throw new IllegalStateException("nmc.webhook.timeout moet korter dan nmc.taak.lease ("
                    + taak.lease() + ") zijn, is " + webhook.timeout());
        }

        if ((long) verzendbudget.verzendReservering() + verzendbudget.navraagReservering() > verzendbudget.tokensPerMinuut()) {
            throw new IllegalStateException("nmc.verzendbudget.verzend-reservering en nmc.verzendbudget.navraag-reservering"
                    + " zijn samen meer dan nmc.verzendbudget.tokens-per-minuut: " + verzendbudget.verzendReservering()
                    + " + " + verzendbudget.navraagReservering() + " > " + verzendbudget.tokensPerMinuut());
        }

        // De rij verdwijnt met de versleutelde gegevens; wissen moet dus eerder of tegelijk.
        if (afleverbewijs.bewaartermijn().compareTo(wissen.termijn()) < 0) {
            throw new IllegalStateException("nmc.afleverbewijs.bewaartermijn (" + afleverbewijs.bewaartermijn()
                    + ") mag niet korter zijn dan nmc.wissen.termijn (" + wissen.termijn() + ")");
        }

        if (fsc.ingeschakeld()) {
            eisFscWaarde("jwks-url", fsc.jwksUrl());
            eisFscWaarde("uitgever", fsc.uitgever());
            eisFscWaarde("inway-adres", fsc.inwayAdres());
            eisFscWaarde("groep", fsc.groep());
            eisFscWaarde("dienst", fsc.dienst());

            // Wie de JWKS onderweg kan vervangen, kan zelf tokens ondertekenen.
            URI jwksUrl = fsc.jwksUrl().get();

            if (!"https".equals(jwksUrl.getScheme()) && !LOKAAL.contains(String.valueOf(jwksUrl.getHost()))) {
                throw new IllegalStateException("nmc.fsc.jwks-url moet https zijn, is " + jwksUrl);
            }
        }
    }

    private static void eisFscWaarde(String naam, Optional<?> waarde) {
        if (waarde.isEmpty()) {
            throw new IllegalStateException("nmc.fsc." + naam + " ontbreekt; verplicht zolang nmc.fsc.ingeschakeld aan staat");
        }
    }
}
