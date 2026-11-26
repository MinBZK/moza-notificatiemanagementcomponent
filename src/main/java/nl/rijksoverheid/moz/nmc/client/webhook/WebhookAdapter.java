package nl.rijksoverheid.moz.nmc.client.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.logging.Log;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.StreamResetException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import nl.rijksoverheid.moz.nmc.config.WebhookConfig;

/**
 * Levert een bundel CloudEvents in één POST aan de webhook van een Dienstverlener. Eén poging: de
 * terugkoppeltaak bepaalt wanneer het opnieuw gaat. De levering telt zodra de status van het antwoord er
 * is; de body van het antwoord wordt niet gelezen en niet in het geheugen gehouden.
 * <p>
 * Een fout aan de kant van de Dienstverlener (geen 2xx, geen verbinding) wordt een
 * {@link WebhookLeveringException}; een fout in het NMC zelf, zoals een serialisatiefout, gaat
 * ongewijzigd door.
 */
@ApplicationScoped
public class WebhookAdapter {

    // Begrenst het aflopen van een eventueel kringvormige oorzakenketen.
    private static final int MAX_OORZAAKDIEPTE = 20;

    private final WebhookClient client;
    private final Duration timeout;

    public WebhookAdapter(WebhookClient client, WebhookConfig config) {
        this.client = client;
        this.timeout = config.timeout();
    }

    /**
     * @param url         de webhook-URL van de Dienstverlener
     * @param autorisatie de waarde van de Authorization-header
     * @param cursor      de cursor van het laatste event in de bundel
     * @param body        de CloudEvents-bundel als JSON
     * @throws WebhookLeveringException als de Dienstverlener de levering niet aannam
     */
    public void lever(String url, String autorisatie, String cursor, String body) {
        Objects.requireNonNull(url, "url is verplicht");

        try {
            // De read-time-out van de client geldt tussen twee stukken antwoord; een webhook die zijn
            // antwoord uitstelt houdt de aanroep anders langer open dan de lease van de taak.
            sluit(client.lever(url, autorisatie, cursor, body)
                    .ifNoItem()
                    .after(timeout)
                    .failWith(() -> new WebhookLeveringException("Webhook antwoordde niet binnen " + timeout))
                    .await()
                    .indefinitely());
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            e.getResponse().close();

            throw new WebhookLeveringException("Webhook antwoordde met HTTP " + status, e);
        } catch (ProcessingException | CompletionException e) {
            throw vertaal(e);
        }
    }

    // Sluiten zonder te lezen: de client gooit de rest van het antwoord dan weg in plaats van het te
    // bewaren. Een antwoord zonder body heeft geen stream.
    private static void sluit(InputStream antwoord) {
        if (antwoord == null) {
            return;
        }

        try {
            antwoord.close();
        } catch (IOException e) {
            Log.debugf(e, "Het antwoord van de webhook kon niet worden gesloten; de levering is wel aangenomen");
        }
    }

    // Alleen een transportfout ligt aan de Dienstverlener. Een serialisatiefout is ook een IOException,
    // maar ligt aan het NMC. Een fout zonder ProcessingException eromheen verpakt await in een
    // CompletionException.
    private static RuntimeException vertaal(RuntimeException e) {
        if (heeftOorzaak(e, InterruptedException.class)) {
            Thread.currentThread().interrupt();

            return new WebhookLeveringException("Webhook-levering onderbroken", e);
        }

        if (heeftOorzaak(e, JsonProcessingException.class)
                || !heeftOorzaak(
                        e,
                        IOException.class,
                        TimeoutException.class,
                        HttpClosedException.class,
                        StreamResetException.class)) {
            return e;
        }

        return new WebhookLeveringException("Webhook niet bereikbaar", e);
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
