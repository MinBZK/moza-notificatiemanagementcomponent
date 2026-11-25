package nl.rijksoverheid.moz.nmc.testhelper;

import nl.rijksoverheid.moz.nmc.client.notifynl.NotifyNLJwtFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Antwoorden van NotifyNL en de stub voor zijn JWT, voor tests die de NotifyNL-client mocken. */
public final class NotifyNLFixtures {

    /** De body waarmee NotifyNL een e-mailadres afwijst: de notificatie is dan niet bezorgbaar. */
    public static final String ADRES_AFGEWEZEN = "{\"errors\":[{\"error\":\"ValidationError\","
            + "\"message\":\"email_address Not a valid email address\"}],\"status_code\":400}";

    /** Een 400 van NotifyNL die niet over het adres gaat: de fout ligt aan het verzoek van het NMC. */
    public static final String TEAM_KEY_FOUT = "{\"errors\":[{\"error\":\"BadRequestError\","
            + "\"message\":\"Can't send to this recipient using a team-only API key\"}],\"status_code\":400}";

    private NotifyNLFixtures() {
    }

    /** Laat de gemockte factory een vaste header geven, zodat de adapter geen echte API-key nodig heeft. */
    public static void stubJwt(NotifyNLJwtFactory notifyNLJwtFactory) {
        when(notifyNLJwtFactory.authorizationHeader(any())).thenReturn("Bearer test-token");
    }
}
