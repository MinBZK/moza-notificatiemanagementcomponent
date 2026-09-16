package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stuurt de afleverstatus van een Notificatie als CloudEvent naar de callback-URL van de
 * Dienstverlener.
 * <p>
 * De aanroep gebeurt ná de commit van NotificatieService.verwerkAfleverstatus(): die vuurt een
 * StatusUpdateOpdracht af die StatusUpdateVerzender bij AFTER_SUCCESS oppakt. Er blijft dus geen
 * DB-connectie openstaan zolang de callback duurt.
 * <p>
 * TODO #732 (zie https://github.com/MinBZK/MijnOverheidZakelijk/issues/732): wat nog wél open staat,
 * is dat deze aanroepen synchroon zijn en de request-thread van de NotifyNL-callback blokkeren, en
 * dat de uitkomst nergens wordt vastgelegd — mislukken alle MAX_POGINGEN, dan is de statusupdate
 * voor de Dienstverlener verloren zonder dat iets hem opnieuw aanbiedt. Beide vragen om een
 * takentabel met eigen herpogingen in plaats van een herpoging-lus in het verzoek.
 */
@ApplicationScoped
public class ConsumentCallbackAdapter {

    private static final int MAX_POGINGEN = 3;

    private final ConsumentCallbackClientFactory clientFactory;
    private final long initieleWachtMs;

    public ConsumentCallbackAdapter(ConsumentCallbackClientFactory clientFactory,
                                     @ConfigProperty(name = "consument-callback.initiele-wacht-ms", defaultValue = "1000") long initieleWachtMs) {
        this.clientFactory = clientFactory;
        this.initieleWachtMs = initieleWachtMs;
    }

    // Alles wat aan de Dienstverlener ligt wordt hier gelogd en niet gegooid. Een fout in de NMC
    // zelf ontsnapt wél (zie de catch hieronder) en wordt door StatusUpdateVerzender op ERROR
    // gelogd; verder dan die observer komt hij niet, want de transactie is dan al gecommit.
    public void stuurStatusUpdate(StatusUpdateOpdracht opdracht) {
        if (opdracht.callbackUrl() == null) {
            Log.infof("Geen callback-URL geconfigureerd voor notificatie %s — statusupdate niet verstuurd", opdracht.notificatieId());

            return;
        }

        String callbackUrl = opdracht.callbackUrl();
        ConsumentCallbackClient client;
        try {
            client = clientFactory.maakClient(callbackUrl);
        } catch (IllegalArgumentException | RestClientDefinitionException e) {
            // Buiten de retry-lus: het bouwen van de client faalt permanent, dus elke poging zou
            // identiek falen.
            //
            // De IllegalArgumentException-tak is sinds CallbackUrlValidator grotendeels dicht: een
            // callbackUrl wordt aan de deur gevalideerd en genormaliseerd voordat hij wordt
            // opgeslagen, dus een vormfout haalt deze code niet meer. Wat overblijft is een rij die
            // van vóór die validatie stamt. De tak blijft staan omdat hij goedkoop is en de enige
            // die dit geval afvangt, niet omdat hij vaak zal vuren.
            //
            // Bewust alleen deze twee typen. Elke andere RuntimeException uit de
            // rest-client-extensie (kapotte truststore, proxyconfiguratie, ontbrekende
            // MessageBodyWriter) is geen probleem van de meegegeven URL en mag hier niet als zodanig
            // weggelogd worden; die ontsnapt naar StatusUpdateVerzender, die hem op ERROR meldt.
            Log.errorf(e, "Callback-client kon niet worden gebouwd voor notificatie %s (url=%s) — "
                    + "statusupdate niet verstuurd", opdracht.notificatieId(), callbackUrl);

            return;
        }

        NotificatieStatusEvent event = new NotificatieStatusEvent(
                "1.0",
                UUID.randomUUID(),
                "nl.rijksoverheid.moz.nmc.notificatie.status",
                "/api/nmc/v1/notificaties/" + opdracht.notificatieId(),
                "notificatie/" + opdracht.notificatieId(),
                OffsetDateTime.now(ZoneOffset.UTC),
                "application/json",
                new NotificatieData(opdracht.notificatieId(), opdracht.status().toApiValue()));

        verstuurMetHerpogingen(client, event, callbackUrl);
    }

    private void verstuurMetHerpogingen(ConsumentCallbackClient client, NotificatieStatusEvent event, String callbackUrl) {
        long wachtMs = initieleWachtMs;
        for (int poging = 1; poging <= MAX_POGINGEN; poging++) {
            try {
                client.stuurStatusUpdate(event);

                return;
            } catch (Exception e) {
                if (poging == MAX_POGINGEN) {
                    Log.warnf(e, "Consument-callback naar %s mislukt na %d pogingen — statusupdate niet "
                            + "afgeleverd aan de Dienstverlener; er volgt geen automatische herpoging",
                            callbackUrl, MAX_POGINGEN);
                } else {
                    Log.warnf(e, "Consument-callback naar %s mislukt (poging %d/%d) — nieuwe poging na %dms",
                            callbackUrl, poging, MAX_POGINGEN, wachtMs);
                    try {
                        Thread.sleep(wachtMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.warnf(ie, "Consument-callback naar %s onderbroken na poging %d", callbackUrl, poging);

                        return;
                    }
                    wachtMs *= 2;
                }
            }
        }
    }
}
