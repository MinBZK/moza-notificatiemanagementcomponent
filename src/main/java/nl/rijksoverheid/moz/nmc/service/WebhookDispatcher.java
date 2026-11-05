package nl.rijksoverheid.moz.nmc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import nl.rijksoverheid.moz.nmc.client.webhook.WebhookAdapter;
import nl.rijksoverheid.moz.nmc.client.webhook.NotificatieStatusEvent;
import nl.rijksoverheid.moz.nmc.client.webhook.WebhookLeveringException;
import nl.rijksoverheid.moz.nmc.domain.Cursor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Levert een bundel events in één aanroep aan de webhook van een Dienstverlener, met een vers
 * ondertekende bearer-JWT ({@code aud} de webhook-URL, {@code body_sha256} de hash van de body) en in
 * de header {@code Nmc-Cursor} de cursor van het laatste event in de bundel. Draait buiten elke
 * transactie.
 */
@ApplicationScoped
public class WebhookDispatcher {

    private final WebhookAdapter webhookAdapter;
    private final WebhookSleutel webhookSleutel;
    private final ObjectMapper objectMapper;

    public WebhookDispatcher(WebhookAdapter webhookAdapter, WebhookSleutel webhookSleutel,
                             ObjectMapper objectMapper) {
        this.webhookAdapter = webhookAdapter;
        this.webhookSleutel = webhookSleutel;
        this.objectMapper = objectMapper;
    }

    /**
     * @param url    de gecontroleerde en genormaliseerde webhook-URL
     * @param cursor de positie van het laatste event in {@code events}
     * @throws WebhookLeveringException als de webhook de bundel niet aannam
     */
    public void lever(String url, List<NotificatieStatusEvent> events, Cursor cursor) {
        // De body gaat als tekst, zodat de hash in de JWT over precies de verzonden bytes gaat.
        String body = serialiseer(events);

        webhookAdapter.lever(url, "Bearer " + webhookSleutel.onderteken(url, hash(body)), cursor.codeer(), body);
    }

    private String serialiseer(List<NotificatieStatusEvent> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("De CloudEvents-bundel is niet te serialiseren", e);
        }
    }

    /** SHA-256 over de UTF-8-bytes van de body, base64url zonder padding. */
    static String hash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
