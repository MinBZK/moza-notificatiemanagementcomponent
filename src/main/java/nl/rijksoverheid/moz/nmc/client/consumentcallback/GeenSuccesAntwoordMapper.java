package nl.rijksoverheid.moz.nmc.client.consumentcallback;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/**
 * Maakt van elk antwoord buiten 2xx een WebApplicationException. De standaardmapper gooit pas vanaf
 * 400, waardoor een niet-gevolgde redirect stil als afgeleverd zou tellen.
 */
public class GeenSuccesAntwoordMapper implements ResponseExceptionMapper<WebApplicationException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        return status >= 300;
    }

    @Override
    public WebApplicationException toThrowable(Response response) {
        return new WebApplicationException("Consument-callback antwoordde met HTTP " + response.getStatus(), response);
    }

    // Vóór de standaardmapper, zodat een 4xx of 5xx dezelfde melding krijgt als een 3xx.
    @Override
    public int getPriority() {
        return 0;
    }
}
