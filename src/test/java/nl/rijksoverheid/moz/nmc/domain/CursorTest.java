package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CursorTest {

    @Test
    void codeerEnDecodeer_gevenDezelfdeCursorTerug() {
        Cursor cursor = new Cursor(3, 9_876_543_210L, 42L);

        assertEquals(cursor, Cursor.decodeer(cursor.codeer()));
    }

    // De cursor is voor de Dienstverlener ondoorzichtig en past zonder escaping in een query-parameter.
    @Test
    void codeer_isUrlVeiligZonderOpvulling() {
        String tekst = new Cursor(1, Long.MAX_VALUE, Long.MAX_VALUE).codeer();

        assertFalse(tekst.contains("=") || tekst.contains("+") || tekst.contains("/"), tekst);
    }

    @ParameterizedTest
    @ValueSource(strings = {"geen base64 !", "YWJj", "MTo6Mg", "LTE6Mjoz"})
    void decodeer_ongeldigeTekst_gooitOngeldigeCursor(String tekst) {
        assertThrows(OngeldigeCursorException.class, () -> Cursor.decodeer(tekst));
    }

    @Test
    void decodeer_getalBuitenBereik_gooitOngeldigeCursor() {
        String tekst = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1:99999999999999999999:1".substring(0, 23).getBytes(StandardCharsets.US_ASCII));
        String teGroot = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1:9999999999999999999:1".getBytes(StandardCharsets.US_ASCII));

        assertThrows(OngeldigeCursorException.class, () -> Cursor.decodeer(tekst));
        assertThrows(OngeldigeCursorException.class, () -> Cursor.decodeer(teGroot));
    }

    @Test
    void constructor_negatiefDeel_weigert() {
        assertThrows(IllegalArgumentException.class, () -> new Cursor(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Cursor(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Cursor(1, 1, -1));
    }
}
