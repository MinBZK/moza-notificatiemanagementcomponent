package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stuurt de afleverstatus als CloudEvent naar de callback-URL van de Dienstverlener, ná de commit
 * (via StatusUpdateVerzender).
 * <p>
 * TODO (buiten scope): de aanroep blokkeert de request-thread van de NotifyNL-callback en na
 * MAX_POGINGEN is de statusupdate verloren; een takentabel met eigen herpogingen lost beide op.
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

    // Een fout aan de kant van de Dienstverlener wordt hier gelogd en niet gegooid. Elke andere fout
    // ontsnapt naar StatusUpdateVerzender, die hem op ERROR logt.
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
            // Buiten de retry-lus: het bouwen van de client faalt permanent. Bewust alleen deze twee
            // typen: een andere RuntimeException uit de rest-client-extensie ligt niet aan de URL en
            // hoort naar StatusUpdateVerzender te ontsnappen. De IllegalArgumentException-tak vangt
            // alleen nog rijen van vóór CallbackUrlValidator af.
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
                NotificatieData.van(opdracht.notificatieId(), opdracht.status()));

        verstuurMetHerpogingen(client, event, opdracht);
    }

    private void verstuurMetHerpogingen(ConsumentCallbackClient client, NotificatieStatusEvent event,
                                        StatusUpdateOpdracht opdracht) {
        long wachtMs = initieleWachtMs;
        for (int poging = 1; poging <= MAX_POGINGEN; poging++) {
            try {
                client.stuurStatusUpdate(event);

                return;
            } catch (WebApplicationException | ProcessingException e) {
                // Alleen een niet-2xx-antwoord of een transportfout ligt aan de Dienstverlener.
                if (poging == MAX_POGINGEN) {
                    Log.errorf(e, "Consument-callback naar %s mislukt na %d pogingen — statusupdate %s voor "
                            + "notificatie %s niet afgeleverd aan de Dienstverlener; er volgt geen automatische "
                            + "herpoging", opdracht.callbackUrl(), MAX_POGINGEN, opdracht.status(), opdracht.notificatieId());
                } else {
                    Log.warnf(e, "Consument-callback naar %s voor notificatie %s (status %s) mislukt (poging %d/%d) "
                            + "— nieuwe poging na %dms", opdracht.callbackUrl(), opdracht.notificatieId(),
                            opdracht.status(), poging, MAX_POGINGEN, wachtMs);
                    try {
                        Thread.sleep(wachtMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Log.warnf(ie, "Consument-callback naar %s onderbroken na poging %d — statusupdate %s voor "
                                + "notificatie %s niet afgeleverd", opdracht.callbackUrl(), poging,
                                opdracht.status(), opdracht.notificatieId());

                        return;
                    }
                    wachtMs *= 2;
                }
            }
        }
    }
}
