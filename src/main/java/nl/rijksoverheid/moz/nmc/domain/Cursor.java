package nl.rijksoverheid.moz.nmc.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Positie in de eventfeed: het cluster-epoch, de transactie-id en het event-id van het laatst
 * gelezen event. Voor de Dienstverlener een ondoorzichtige tekst: base64url van
 * {@code epoch:transactieId:eventId}.
 *
 * @param epoch        het cluster-epoch waarin de cursor is uitgegeven; beheer verhoogt het bij herstel
 *                     uit back-up, waarna oudere cursors vervallen
 * @param transactieId de transactie-id van het event, als getal
 * @param eventId      het id van het event
 */
public record Cursor(int epoch, long transactieId, long eventId) {

    private static final Pattern CURSOR_PATROON = Pattern.compile("([0-9]{1,9}):([0-9]{1,19}):([0-9]{1,19})");

    public Cursor {
        if (epoch < 0 || transactieId < 0 || eventId < 0) {
            throw new IllegalArgumentException("Een cursor heeft geen negatieve delen: " + epoch + ":" + transactieId + ":" + eventId);
        }
    }

    public String codeer() {
        String tekst = epoch + ":" + transactieId + ":" + eventId;

        return Base64.getUrlEncoder().withoutPadding().encodeToString(tekst.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @throws OngeldigeCursorException als de tekst geen door {@link #codeer()} gemaakte cursor is
     */
    public static Cursor decodeer(String tekst) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(tekst);
        } catch (IllegalArgumentException e) {
            throw new OngeldigeCursorException("Cursor is geen geldige base64url-tekst");
        }

        var match = CURSOR_PATROON.matcher(new String(bytes, StandardCharsets.US_ASCII));

        if (!match.matches()) {
            throw new OngeldigeCursorException("Cursor heeft niet de vorm epoch:transactieId:eventId");
        }

        try {
            return new Cursor(Integer.parseInt(match.group(1)), Long.parseLong(match.group(2)), Long.parseLong(match.group(3)));
        } catch (NumberFormatException e) {
            throw new OngeldigeCursorException("Cursor bevat een getal buiten bereik");
        }
    }
}
