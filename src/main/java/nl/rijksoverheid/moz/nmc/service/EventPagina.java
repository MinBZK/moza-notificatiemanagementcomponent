package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Event;

import java.util.List;

/**
 * Eén pagina uit de feed.
 *
 * @param events de events op volgorde van transactie-id
 * @param cursor de positie ná het laatste event; bij een lege pagina de meegegeven cursor, en null
 *               als er nog geen cursor was
 */
public record EventPagina(List<Event> events, Cursor cursor) {
}
