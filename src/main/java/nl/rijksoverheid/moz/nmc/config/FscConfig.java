package nl.rijksoverheid.moz.nmc.config;

import io.smallrye.config.ConfigMapping;
import org.hibernate.validator.constraints.time.DurationMin;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * De controle van het FSC-toegangstoken onder {@code nmc.fsc}. Een ongeldige waarde laat de
 * applicatie niet starten; {@link ConfigValidatie} eist de waarden die alleen met de controle aan
 * verplicht zijn.
 */
@ConfigMapping(prefix = "nmc.fsc")
public interface FscConfig {

    /**
     * Of elke aanroep van een dienstverlener een geldig FSC-toegangstoken moet dragen. Uit betekent:
     * geen controle, en elke aanroep telt voor de dienstverlener uit {@code nmc.dienstverlener.id}.
     */
    boolean ingeschakeld();

    /**
     * Het adres van de JWKS van de Manager die de tokens uitgeeft.
     */
    Optional<URI> jwksUrl();

    /**
     * De verwachte {@code iss}: het PeerID van de peer die het NMC aanbiedt.
     */
    Optional<String> uitgever();

    /**
     * De verwachte {@code aud}: het adres van de inway waarop het NMC is gepubliceerd, letterlijk zoals
     * de Manager het in het token zet.
     */
    Optional<String> inwayAdres();

    /**
     * De verwachte {@code gid}: de FSC-groep.
     */
    Optional<String> groep();

    /**
     * De verwachte {@code svc}: de naam waaronder het NMC op de inway is gepubliceerd.
     */
    Optional<String> dienst();

    /**
     * Hoeveel de klok van de Manager mag afwijken bij de toets van {@code exp} en {@code nbf}.
     */
    @DurationMin
    Duration klokmarge();

    /**
     * De time-out voor het verbinden met en het lezen van de JWKS, elk afzonderlijk.
     */
    @DurationMin(millis = 1)
    Duration jwksTimeout();

    /**
     * Hoe lang een opgehaalde JWKS wordt bewaard voordat hij opnieuw wordt opgehaald. De bewaartijd die
     * de Manager in {@code Cache-Control} of {@code Expires} opgeeft, telt niet.
     */
    @DurationMin(seconds = 1)
    Duration jwksBewaartijd();

    /**
     * Hoe lang de bewaarde sleutels geldig blijven nadat het ophalen van de JWKS voor het eerst
     * mislukte. Daarna is geen token meer te controleren tot het ophalen weer lukt.
     */
    @DurationMin
    Duration jwksStoringstolerantie();
}
