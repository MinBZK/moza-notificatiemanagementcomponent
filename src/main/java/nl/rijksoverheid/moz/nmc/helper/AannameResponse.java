package nl.rijksoverheid.moz.nmc.helper;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;

import java.util.UUID;

public final class AannameResponse {

    private static final String NOTIFICATIE_PAD = "/api/nmc/v1/notificaties/";

    private AannameResponse() {
    }

    /**
     * 202 met het id in de body en een relatieve {@code Location}; {@code header} in plaats van
     * {@code location}, omdat die laatste het pad absoluut maakt op de host van het verzoek.
     */
    public static Response notificatieAangenomen(UUID notificatieId) {
        return Response.accepted(new NotificatieResponse(notificatieId))
                .header(HttpHeaders.LOCATION, NOTIFICATIE_PAD + notificatieId)
                .build();
    }
}
