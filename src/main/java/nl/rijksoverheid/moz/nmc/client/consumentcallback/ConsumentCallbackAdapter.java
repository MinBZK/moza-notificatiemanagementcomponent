package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.StreamResetException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.RestClientDefinitionException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Levert een bundel CloudEvents in één POST aan de webhook van een Dienstverlener. Eén poging: de
 * terugkoppeltaak bepaalt wanneer het opnieuw gaat.
 * <p>
 * Een fout aan de kant van de Dienstverlener (geen 2xx, geen verbinding, een URL waar geen client van
 * te bouwen is) wordt een {@link WebhookLeveringException}; een fout in het NMC zelf, zoals een
 * serialisatiefout, gaat ongewijzigd door.
 */
@ApplicationScoped
public class ConsumentCallbackAdapter {

    // Begrenst het aflopen van een eventueel kringvormige oorzakenketen.
    private static final int MAX_OORZAAKDIEPTE = 20;

    private final ConsumentCallbackClientFactory clientFactory;

    public ConsumentCallbackAdapter(ConsumentCallbackClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * @param autorisatie de waarde van de Authorization-header
     * @param cursor      de cursor van het laatste event in de bundel
     * @throws WebhookLeveringException als de Dienstverlener de levering niet aannam
     */
    public void lever(String url, String autorisatie, String cursor, List<NotificatieStatusEvent> events) {
        ConsumentCallbackClient client;
        try {
            client = clientFactory.maakClient(url);
        } catch (IllegalArgumentException | RestClientDefinitionException e) {
            throw new WebhookLeveringException("Webhook-client kon niet worden gebouwd", e);
        }

        try {
            client.lever(autorisatie, cursor, events);
        } catch (WebApplicationException e) {
            throw new WebhookLeveringException("Webhook antwoordde met HTTP " + e.getResponse().getStatus(), e);
        } catch (ProcessingException e) {
            throw vertaal(e);
        } finally {
            sluit(client);
        }
    }

    // De rest-client pakt elke fout in als ProcessingException, ook een fout in het NMC zelf; alleen
    // een transportfout ligt aan de Dienstverlener. Jacksons JsonProcessingException is een
    // IOException, maar een serialisatiefout ligt aan het NMC.
    private static RuntimeException vertaal(ProcessingException e) {
        if (heeftOorzaak(e, InterruptedException.class)) {
            Thread.currentThread().interrupt();

            return new WebhookLeveringException("Webhook-levering onderbroken", e);
        }

        if (heeftOorzaak(e, JsonProcessingException.class)
                || !heeftOorzaak(e, IOException.class, TimeoutException.class, HttpClosedException.class, StreamResetException.class)) {
            return e;
        }

        return new WebhookLeveringException("Webhook niet bereikbaar", e);
    }

    // Een fout bij het sluiten zegt niets over de levering.
    private static void sluit(ConsumentCallbackClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            Log.warn("Webhook-client niet netjes gesloten", e);
        }
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
