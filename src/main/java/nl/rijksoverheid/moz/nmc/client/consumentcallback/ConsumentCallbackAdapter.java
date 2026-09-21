package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import io.quarkus.logging.Log;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.StreamResetException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

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

    // Begrenst het aflopen van een eventueel kringvormige oorzakenketen.
    private static final int MAX_OORZAAKDIEPTE = 20;

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
            // Buiten de retry-lus: een client die niet te bouwen is, faalt permanent. Andere
            // RuntimeExceptions ontsnappen bewust naar StatusUpdateVerzender.
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
                new NotificatieData(opdracht.notificatieId(), opdracht.status()));

        try (client) {
            verstuurMetHerpogingen(client, event, opdracht);
        }
    }

    private void verstuurMetHerpogingen(ConsumentCallbackClient client, NotificatieStatusEvent event,
                                        StatusUpdateOpdracht opdracht) {
        long wachtMs = initieleWachtMs;
        for (int poging = 1; ; poging++) {
            RuntimeException fout;
            try {
                client.stuurStatusUpdate(event);

                return;
            } catch (WebApplicationException e) {
                fout = e;
            } catch (ProcessingException e) {
                // De rest-client pakt elke fout in als ProcessingException, ook een fout in de NMC zelf;
                // alleen een transportfout ligt aan de Dienstverlener.
                if (heeftOorzaak(e, InterruptedException.class)) {
                    meldOnderbroken(e, opdracht, poging);

                    return;
                }

                if (!heeftOorzaak(e, IOException.class, TimeoutException.class, HttpClosedException.class,
                        StreamResetException.class)) {
                    throw e;
                }

                fout = e;
            }

            if (poging == MAX_POGINGEN) {
                Log.errorf(fout, "Consument-callback naar %s mislukt na %d pogingen — statusupdate %s voor "
                        + "notificatie %s niet afgeleverd aan de Dienstverlener; er volgt geen automatische "
                        + "herpoging", opdracht.callbackUrl(), MAX_POGINGEN, opdracht.status(), opdracht.notificatieId());

                return;
            }

            Log.warnf(fout, "Consument-callback naar %s voor notificatie %s (status %s) mislukt (poging %d/%d) "
                    + "— nieuwe poging na %dms", opdracht.callbackUrl(), opdracht.notificatieId(),
                    opdracht.status(), poging, MAX_POGINGEN, wachtMs);
            try {
                Thread.sleep(wachtMs);
            } catch (InterruptedException e) {
                meldOnderbroken(e, opdracht, poging);

                return;
            }
            wachtMs *= 2;
        }
    }

    // ERROR: de statusupdate is hiermee definitief verloren.
    private static void meldOnderbroken(Exception e, StatusUpdateOpdracht opdracht, int poging) {
        Thread.currentThread().interrupt();
        Log.errorf(e, "Consument-callback naar %s onderbroken bij poging %d — statusupdate %s voor notificatie %s "
                + "niet afgeleverd", opdracht.callbackUrl(), poging, opdracht.status(), opdracht.notificatieId());
    }

    @SafeVarargs
    private static boolean heeftOorzaak(Throwable fout, Class<? extends Throwable>... typen) {
        Throwable oorzaak = fout.getCause();
        for (int diepte = 0; oorzaak != null && diepte < MAX_OORZAAKDIEPTE; diepte++) {
            for (Class<? extends Throwable> type : typen) {
                if (type.isInstance(oorzaak)) {
                    return true;
                }
            }

            oorzaak = oorzaak.getCause();
        }

        return false;
    }
}
