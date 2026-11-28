package nl.rijksoverheid.moz.nmc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TransactieIdTypeTest {

    private final TransactieIdType type = new TransactieIdType();

    @Test
    void nullSafeGet_getal_wordtLong() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("9876543210");

        assertEquals(9_876_543_210L, type.nullSafeGet(rs, 1, null));
    }

    @Test
    void nullSafeGet_null_blijftNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        assertNull(type.nullSafeGet(rs, 1, null));
    }

    // xid8 is unsigned 64 bits; het grootste getal past niet in een long.
    @Test
    void nullSafeGet_groterDanLong_geeftSqlException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn("18446744073709551615");

        SQLException e = assertThrows(SQLException.class, () -> type.nullSafeGet(rs, 2, null));

        assertTrue(e.getMessage().contains("18446744073709551615"), e.getMessage());
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    // Hibernate vergelijkt hiermee bij dirty checking; gelijke waarden in verschillende Long-objecten zijn gelijk.
    @Test
    void equals_vergelijktOpWaardeEnVerdraagtNull() {
        assertTrue(type.equals(Long.valueOf(9_876_543_210L), Long.valueOf(9_876_543_210L)));
        assertFalse(type.equals(1L, 2L));
        assertFalse(type.equals(1L, null));
        assertTrue(type.equals(null, null));
    }

    @Test
    void hashCode_volgtDeWaardeEnIsNulVoorNull() {
        assertEquals(Long.hashCode(9_876_543_210L), type.hashCode(9_876_543_210L));
        assertNotEquals(type.hashCode(1L), type.hashCode(2L));
        assertEquals(0, type.hashCode(null));
    }

    // Een Long is onveranderlijk; de cache mag dezelfde instantie bewaren en teruggeven.
    @Test
    void disassembleEnAssemble_gevenDezelfdeWaardeTerug() {
        Long waarde = 9_876_543_210L;

        assertSame(waarde, type.disassemble(waarde));
        assertSame(waarde, type.assemble(waarde, null));
        assertNull(type.assemble(null, null));
    }
}
