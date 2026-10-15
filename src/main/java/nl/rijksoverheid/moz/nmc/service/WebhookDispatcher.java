package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.ConsumentCallbackAdapter;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.NotificatieStatusEvent;
import nl.rijksoverheid.moz.nmc.client.consumentcallback.WebhookLeveringException;
import nl.rijksoverheid.moz.nmc.domain.Cursor;

import java.util.List;

/**
 * Levert een bundel events in één aanroep aan de webhook van een Dienstverlener, met een vers
 * ondertekende bearer-JWT ({@code aud} de webhook-URL) en in de header {@code Nmc-Cursor} de cursor
 * van het laatste event in de bundel. Draait buiten elke transactie.
 */
@ApplicationScoped
public class WebhookDispatcher {

    private final ConsumentCallbackAdapter consumentCallbackAdapter;
    private final WebhookSleutel webhookSleutel;

    public WebhookDispatcher(ConsumentCallbackAdapter consumentCallbackAdapter, WebhookSleutel webhookSleutel) {
        this.consumentCallbackAdapter = consumentCallbackAdapter;
        this.webhookSleutel = webhookSleutel;
    }

    /**
     * @param url    de gecontroleerde en genormaliseerde webhook-URL
     * @param cursor de positie van het laatste event in {@code events}
     * @throws WebhookLeveringException als de webhook de bundel niet aannam
     */
    public void lever(String url, List<NotificatieStatusEvent> events, Cursor cursor) {
        consumentCallbackAdapter.lever(url, "Bearer " + webhookSleutel.onderteken(url), cursor.codeer(), events);
    }
}
