package nl.rijksoverheid.moz.nmc.domain;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * Een PostgreSQL-{@code xid8} als {@code Long}. De waarde gaat als tekst zonder type naar de database,
 * zodat PostgreSQL er een {@code xid8} van maakt en een vergelijking de index op de kolom gebruikt.
 */
public class TransactieIdType implements UserType<Long> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<Long> returnedClass() {
        return Long.class;
    }

    @Override
    public boolean equals(Long x, Long y) {
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode(Long x) {
        return Objects.hashCode(x);
    }

    @Override
    public Long nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        String waarde = rs.getString(position);

        if (waarde == null) {
            return null;
        }

        try {
            return Long.valueOf(waarde);
        } catch (NumberFormatException e) {
            throw new SQLException("Transactie-id " + waarde + " in kolom " + position + " past niet in een long", e);
        }
    }

    @Override
    public void nullSafeSet(PreparedStatement st, Long value, int index, WrapperOptions options) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value.toString(), Types.OTHER);
        }
    }

    @Override
    public Long deepCopy(Long value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(Long value) {
        return value;
    }

    @Override
    public Long assemble(Serializable cached, Object owner) {
        return (Long) cached;
    }
}
