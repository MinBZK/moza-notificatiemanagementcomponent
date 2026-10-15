package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
