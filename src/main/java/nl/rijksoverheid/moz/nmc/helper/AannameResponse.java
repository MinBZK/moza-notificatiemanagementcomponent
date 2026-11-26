package nl.rijksoverheid.moz.nmc.helper;

import jakarta.ws.rs.core.Response;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.api.model.NotificatieResponse;

public final class AannameResponse {

    private AannameResponse() {}

    /** 202 met het id in de body. */
    public static Response notificatieAangenomen(UUID notificatieId) {
        return Response.accepted(new NotificatieResponse(notificatieId)).build();
    }
}
