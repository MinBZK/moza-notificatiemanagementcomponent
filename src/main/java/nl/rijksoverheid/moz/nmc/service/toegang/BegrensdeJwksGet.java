package nl.rijksoverheid.moz.nmc.service.toegang;

import org.jose4j.http.SimpleGet;
import org.jose4j.http.SimpleResponse;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.lang.JoseException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * De ophaler van de JWKS voor {@link FscTokenValidator}, die begrenst hoe vaak de Manager wordt
 * aangeroepen. Binnen de wachttijd na een poging gaat er geen nieuwe aanroep uit: na een mislukte
 * poging volgt direct een {@link IOException}, na een geslaagde het vorige antwoord. Zonder die grens
 * doet jose4j bij een lege sleutelset voor elke aanroep van een dienstverlener een eigen ophaling,
 * na elkaar. Een antwoord dat geen JWKS is, telt als mislukte poging. De ophaler houdt ook bij sinds
 * wanneer het ophalen mislukt, en geeft de bewaartijd die de Manager opgeeft niet door.
 */
final class BegrensdeJwksGet implements SimpleGet {

    private final SimpleGet get;
    private final Clock clock;
    private final long minimumTussenOphalenMs;

    private boolean eerderGeprobeerd;
    private long eindeLaatstePoging;
    // Leeg na een mislukte poging.
    private SimpleResponse laatsteAntwoord;
    // Het einde van de eerste mislukte poging sinds de laatste geslaagde; leeg zolang het ophalen lukt.
    private Long storingSinds;

    /**
     * @param get                    de ophaler die de Manager echt aanroept
     * @param clock                  de klok waarop de wachttijd loopt
     * @param minimumTussenOphalenMs de wachttijd na het einde van een poging, in milliseconden
     */
    BegrensdeJwksGet(SimpleGet get, Clock clock, long minimumTussenOphalenMs) {
        this.get = get;
        this.clock = clock;
        this.minimumTussenOphalenMs = minimumTussenOphalenMs;
    }

    /**
     * @throws IOException als het ophalen mislukt, het antwoord geen JWKS is, of de vorige poging
     *                     mislukte en de wachttijd nog loopt
     */
    @Override
    public synchronized SimpleResponse get(String url) throws IOException {
        if (eerderGeprobeerd && clock.millis() - eindeLaatstePoging < minimumTussenOphalenMs) {
            if (laatsteAntwoord == null) {
                throw new IOException("De vorige poging om de JWKS op te halen mislukte; de wachttijd voor een nieuwe poging loopt nog");
            }

            return laatsteAntwoord;
        }

        eerderGeprobeerd = true;
        laatsteAntwoord = null;

        try {
            SimpleResponse antwoord = new ZonderBewaartijd(get.get(url));
            eisSleutelset(antwoord.getBody());
            laatsteAntwoord = antwoord;
            storingSinds = null;

            return antwoord;
        } catch (IOException e) {
            if (storingSinds == null) {
                storingSinds = clock.millis();
            }

            throw e;
        } finally {
            eindeLaatstePoging = clock.millis();
        }
    }

    /**
     * @param duur de duur waartegen de storing wordt afgezet
     * @return of het ophalen al langer dan deze duur mislukt, gerekend vanaf de eerste mislukte poging
     *         sinds de laatste geslaagde
     */
    synchronized boolean storingLangerDan(Duration duur) {
        return storingSinds != null && clock.millis() - storingSinds > duur.toMillis();
    }

    // Een foutpagina met status 200 is een storing of een verkeerd adres, geen afgekeurd token.
    private static void eisSleutelset(String body) throws IOException {
        try {
            new JsonWebKeySet(body);
        } catch (JoseException e) {
            throw new IOException("Het antwoord op het ophalen van de JWKS is geen JSON Web Key Set", e);
        }
    }

    /**
     * Het antwoord van de Manager zonder {@code Cache-Control} en {@code Expires}. jose4j bewaart de
     * sleutels anders zo lang als die headers opgeven, en een sleutel die de Manager intrekt blijft dan
     * al die tijd geldig.
     *
     * @param antwoord het antwoord zoals de Manager het gaf
     */
    private record ZonderBewaartijd(SimpleResponse antwoord) implements SimpleResponse {

        @Override
        public int getStatusCode() {
            return antwoord.getStatusCode();
        }

        @Override
        public String getStatusMessage() {
            return antwoord.getStatusMessage();
        }

        @Override
        public Collection<String> getHeaderNames() {
            return antwoord.getHeaderNames().stream().filter(naam -> !zegtBewaartijd(naam)).toList();
        }

        @Override
        public List<String> getHeaderValues(String naam) {
            return zegtBewaartijd(naam) ? List.of() : antwoord.getHeaderValues(naam);
        }

        @Override
        public String getBody() {
            return antwoord.getBody();
        }

        private static boolean zegtBewaartijd(String naam) {
            return "Cache-Control".equalsIgnoreCase(naam) || "Expires".equalsIgnoreCase(naam);
        }
    }
}
