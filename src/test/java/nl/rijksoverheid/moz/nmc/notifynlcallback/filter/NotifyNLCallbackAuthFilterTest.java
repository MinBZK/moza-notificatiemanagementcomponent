package nl.rijksoverheid.moz.nmc.notifynlcallback.filter;

import io.quarkiverse.httpproblem.HttpProblem;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotifyNLCallbackAuthFilterTest {

    private final NotifyNLCallbackAuthFilter filter = new NotifyNLCallbackAuthFilter(Optional.of("geheim-token"));

    @Test
    void constructor_ontbrekendOfLeegToken_gooitIllegalStateException() {
        IllegalStateException ontbrekend = assertThrows(IllegalStateException.class,
                () -> new NotifyNLCallbackAuthFilter(Optional.empty()));
        assertThrows(IllegalStateException.class, () -> new NotifyNLCallbackAuthFilter(Optional.of(" ")));

        assertEquals("notify.callback.bearer-token is niet geconfigureerd", ontbrekend.getMessage());
    }

    @Test
    void filter_juistBearerToken_laatDoor() {
        assertDoesNotThrow(() -> filter.filter(verzoekMet("Bearer geheim-token")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "geheim-token", "Basic geheim-token", "bearer geheim-token", "Bearer ander-token",
            "Bearer geheim-token-langer", "Bearer "})
    void filter_ontbrekendOfOngeldigToken_weigertMet401(String authorization) {
        HttpProblem fout = assertThrows(HttpProblem.class, () -> filter.filter(verzoekMet(authorization)));

        assertEquals(401, fout.getStatusCode());
    }

    private static ContainerRequestContext verzoekMet(String authorization) {
        ContainerRequestContext verzoek = mock(ContainerRequestContext.class);
        when(verzoek.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(authorization);

        return verzoek;
    }
}
