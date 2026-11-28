package nl.rijksoverheid.moz.nmc.client.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GeenSuccesAntwoordMapperTest {

    private final GeenSuccesAntwoordMapper mapper = new GeenSuccesAntwoordMapper();

    // Ook een redirect is geen levering: de client volgt hem niet.
    @Test
    void handles_vanaf300_isFout() {
        assertFalse(mapper.handles(200, new MultivaluedHashMap<>()));
        assertFalse(mapper.handles(299, new MultivaluedHashMap<>()));
        assertTrue(mapper.handles(300, new MultivaluedHashMap<>()));
        assertTrue(mapper.handles(302, new MultivaluedHashMap<>()));
        assertTrue(mapper.handles(503, new MultivaluedHashMap<>()));
    }

    @Test
    void toThrowable_noemtDeStatusEnHoudtHetAntwoord() {
        Response antwoord = Response.status(302).build();

        WebApplicationException fout = mapper.toThrowable(antwoord);

        assertEquals("Webhook antwoordde met HTTP 302", fout.getMessage());
        assertSame(antwoord, fout.getResponse());
    }

    // Een lager getal gaat voor; de standaardmapper van de client heeft de standaardprioriteit.
    @Test
    void getPriority_gaatVoorDeStandaardmapper() {
        assertTrue(mapper.getPriority() < ResponseExceptionMapper.DEFAULT_PRIORITY);
    }
}
