package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stuurt de afleverstatus van een Notificatie als CloudEvent naar de callback-URL van de
 * Dienstverlener.
 * <p>
 * TODO #732 (zie https://github.com/MinBZK/MijnOverheidZakelijk/issues/732): deze HTTP-aanroepen
 * gebeuren binnen de actieve @Transactional-context van NotificatieService.verwerkAfleverstatus(),
 * waardoor een DB-connectie openblijft zolang de callback duurt — inclusief de herpogingen
 * hieronder. Onder belasting kan dit de connection pool uitputten; haal de HTTP-aanroep vóór
 * go-live buiten de transactie (of maak hem asynchroon/event-gedreven).
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

    // status wordt meegegeven i.p.v. hier notificatie.getStatus() te lezen: die laatste is afgeleid
    // van een lazy @ElementCollection (Notificatie#statusGeschiedenis) en kan dus — in tegenstelling
    // tot een aanroep die de al-opgehaalde status doorgeeft — in theorie een exception opleveren.
    // Alles wat aan de Dienstverlener ligt (geen of een ongeldige callback-URL, een onbereikbaar
    // endpoint) wordt hier afgevangen: de aanroeper zit nog in een actieve transactie waarvan een net
    // verwerkte NotifyNL-statusupdate anders verloren zou gaan. Een fout in de NMC-configuratie zelf
    // ontsnapt bewust wél (zie de catch hieronder). Geeft bewust niets terug: het
    // resultaat van de callback wordt nergens vastgelegd of opnieuw aangeboden, dus een
    // returnwaarde zou een opvolging suggereren die er niet is (zie TODO #732).
    public void stuurStatusUpdate(Notificatie notificatie, StatusWaarde status) {
        if (notificatie.getCallbackUrl() == null) {
            Log.infof("Geen callback-URL geconfigureerd voor notificatie %s — statusupdate niet verstuurd", notificatie.getId());

            return;
        }

        String callbackUrl = notificatie.getCallbackUrl();
        ConsumentCallbackClient client;
        try {
            client = clientFactory.maakClient(callbackUrl);
        } catch (IllegalArgumentException | RestClientDefinitionException e) {
            // Buiten de retry-lus: het bouwen van de client faalt permanent, dus elke poging zou
            // identiek falen. Ongevangen zou dit de aanroepende @Transactional-methode laten
            // rollbacken, met als gevolg dat ook de zojuist verwerkte NotifyNL-statusupdate verloren
            // gaat — zie NotificatieService.verwerkAfleverstatus. Bewust alleen deze twee: een
            // ongeldige URL (URI.create) en een fout in onze eigen client-interface. Elke andere
            // RuntimeException uit de rest-client-extensie (kapotte truststore, proxyconfiguratie,
            // ontbrekende MessageBodyWriter) is geen probleem van de meegegeven URL en mag hier niet
            // als zodanig weggelogd worden.
            Log.errorf(e, "Callback-client kon niet worden gebouwd voor notificatie %s (url=%s) — "
                    + "statusupdate niet verstuurd", notificatie.getId(), callbackUrl);

            return;
        }

        NotificatieStatusEvent event = new NotificatieStatusEvent(
                "1.0",
                UUID.randomUUID(),
                "nl.rijksoverheid.moz.nmc.notificatie.status",
                "/api/nmc/v1/notificaties/" + notificatie.getId(),
                "notificatie/" + notificatie.getId(),
                OffsetDateTime.now(ZoneOffset.UTC),
                "application/json",
                new NotificatieData(notificatie.getId(), status));

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
                    Log.errorf(e, "Consument-callback naar %s mislukt na %d pogingen — statusupdate niet "
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
