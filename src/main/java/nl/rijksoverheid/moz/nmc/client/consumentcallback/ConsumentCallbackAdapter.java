package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.StreamResetException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.io.IOException;
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

    private static final String TYPE_PREFIX = "nl.mijnoverheidzakelijk.nmc.notificatie.status.";

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
    public void stuurStatusUpdate(StatusUpdateOpdracht statusUpdateOpdracht) {
        if (statusUpdateOpdracht.callbackUrl() == null) {
            Log.infof("Geen callback-URL geconfigureerd voor notificatie %s — statusupdate niet verstuurd",
                    statusUpdateOpdracht.notificatieId());

            return;
        }

        String callbackUrl = statusUpdateOpdracht.callbackUrl();
        ConsumentCallbackClient client;
        try {
            client = clientFactory.maakClient(callbackUrl);
        } catch (IllegalArgumentException | RestClientDefinitionException e) {
            // Buiten de retry-lus: een client die niet te bouwen is, faalt permanent. Andere
            // RuntimeExceptions ontsnappen bewust naar StatusUpdateVerzender.
            Log.errorf(e, "Callback-client kon niet worden gebouwd voor notificatie %s (url=%s) — "
                    + "statusupdate niet verstuurd", statusUpdateOpdracht.notificatieId(), callbackUrl);

            return;
        }

        NotificatieStatusEvent event = new NotificatieStatusEvent(
                "1.0",
                UUID.randomUUID(),
                TYPE_PREFIX + statusUpdateOpdracht.nieuweStatus().toApiValue(),
                "/api/nmc/v1/notificaties/" + statusUpdateOpdracht.notificatieId(),
                statusUpdateOpdracht.notificatieId().toString(),
                statusUpdateOpdracht.tijdstip(),
                "application/json",
                // De sequence-extensie van CloudEvents schrijft een string voor.
                String.valueOf(statusUpdateOpdracht.versie()),
                "Integer",
                new NotificatieData(statusUpdateOpdracht.vorigeStatus(), statusUpdateOpdracht.nieuweStatus(),
                        statusUpdateOpdracht.reden(), statusUpdateOpdracht.versie()));

        try {
            verstuurMetHerpogingen(client, event, statusUpdateOpdracht);
        } finally {
            sluit(client, statusUpdateOpdracht);
        }
    }

    // Een fout bij het sluiten zegt niets over de aflevering; doorgooien zou een afgeleverde update
    // in StatusUpdateVerzender als verloren laten melden.
    private static void sluit(ConsumentCallbackClient client, StatusUpdateOpdracht statusUpdateOpdracht) {
        try {
            client.close();
        } catch (RuntimeException e) {
            Log.warnf(e, "Callback-client voor notificatie %s niet netjes gesloten", statusUpdateOpdracht.notificatieId());
        }
    }

    private void verstuurMetHerpogingen(ConsumentCallbackClient client, NotificatieStatusEvent event,
                                        StatusUpdateOpdracht statusUpdateOpdracht) {
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
                    meldOnderbroken(e, statusUpdateOpdracht, poging);

                    return;
                }

                // Jacksons JsonProcessingException is een IOException, maar een serialisatiefout ligt
                // aan de NMC.
                if (heeftOorzaak(e, JsonProcessingException.class) || !heeftOorzaak(e, IOException.class, TimeoutException.class, HttpClosedException.class,
                        StreamResetException.class)) {
                    throw e;
                }

                fout = e;
            }

            if (poging == MAX_POGINGEN) {
                Log.errorf(fout, "Consument-callback naar %s mislukt na %d pogingen — statusupdate %s voor "
                        + "notificatie %s niet afgeleverd aan de Dienstverlener; er volgt geen automatische "
                        + "herpoging", statusUpdateOpdracht.callbackUrl(), MAX_POGINGEN,
                        statusUpdateOpdracht.nieuweStatus(), statusUpdateOpdracht.notificatieId());

                return;
            }

            Log.warnf(fout, "Consument-callback naar %s voor notificatie %s (status %s) mislukt (poging %d/%d) "
                    + "— nieuwe poging na %dms", statusUpdateOpdracht.callbackUrl(), statusUpdateOpdracht.notificatieId(),
                    statusUpdateOpdracht.nieuweStatus(), poging, MAX_POGINGEN, wachtMs);
            try {
                Thread.sleep(wachtMs);
            } catch (InterruptedException e) {
                meldOnderbroken(e, statusUpdateOpdracht, poging);

                return;
            }
            wachtMs *= 2;
        }
    }

    // ERROR: de statusupdate is hiermee definitief verloren.
    private static void meldOnderbroken(Exception e, StatusUpdateOpdracht statusUpdateOpdracht, int poging) {
        Thread.currentThread().interrupt();
        Log.errorf(e, "Consument-callback naar %s onderbroken bij poging %d — statusupdate %s voor notificatie %s "
                + "niet afgeleverd", statusUpdateOpdracht.callbackUrl(), poging, statusUpdateOpdracht.nieuweStatus(),
                statusUpdateOpdracht.notificatieId());
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
