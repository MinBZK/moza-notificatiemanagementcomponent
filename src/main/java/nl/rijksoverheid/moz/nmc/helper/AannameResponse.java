package nl.rijksoverheid.moz.nmc.helper;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;

import java.util.UUID;

public final class AannameResponse {

    private AannameResponse() {
    }

    /**
     * 202 met het id in de body en een relatieve {@code Location}; {@code header} in plaats van
     * {@code location}, omdat die laatste het pad absoluut maakt op de host van het verzoek.
     */
    public static Response notificatieAangenomen(UUID notificatieId) {
        return Response.accepted(new NotificatieResponse(notificatieId))
                .header(HttpHeaders.LOCATION, Notificatie.PAD + notificatieId)
                .build();
    }
}
