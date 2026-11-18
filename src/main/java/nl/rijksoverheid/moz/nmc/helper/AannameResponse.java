package nl.rijksoverheid.moz.nmc.helper;

import jakarta.ws.rs.core.Response;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;

import java.util.UUID;

public final class AannameResponse {

    private AannameResponse() {
    }

    /** 202 met het id in de body. */
    public static Response notificatieAangenomen(UUID notificatieId) {
        return Response.accepted(new NotificatieResponse(notificatieId)).build();
    }
}
