package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;

import java.util.List;

/**
 * Handgeschreven REST-client voor de webhook van een Dienstverlener: de URL komt uit het register en
 * is pas bij het leveren bekend, dus er is geen vast contract om hem uit te genereren.
 */
public interface ConsumentCallbackClient extends AutoCloseable {

    /** De header met de cursor van het laatste event in de bundel. */
    String CURSOR_HEADER = "Nmc-Cursor";

    /** POST de events als CloudEvents-batch naar de webhook waarvoor deze client is gebouwd. */
    @POST
    @Consumes("application/cloudevents-batch+json")
    void lever(@HeaderParam("Authorization") String autorisatie, @HeaderParam(CURSOR_HEADER) String cursor,
               List<NotificatieStatusEvent> events);

    // Elke levering bouwt een eigen client; sluiten geeft zijn HTTP-verbindingen vrij.
    @Override
    void close();
}
