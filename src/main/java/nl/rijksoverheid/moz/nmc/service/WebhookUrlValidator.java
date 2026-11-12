package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Controleert de webhook-URL van een Dienstverlener vóór elke levering, zodat de terugkoppeltaak niet
 * naar een interne bestemming POST (SSRF). Toegestaan: een absolute https-URL naar een publieke
 * hostnaam van ten hoogste {@value #MAX_LENGTE} tekens. Geweigerd: andere schema's, gebruikersinfo,
 * een IP-adres als host en interne hostnamen (localhost, namen zonder punt, *.localhost/*.local/
 * *.internal/*.svc, *.home.arpa/*.home/*.corp/*.lan/*.intranet).
 * <p>
 * Dit is een denylist op vorm, geen volledige SSRF-bescherming: een hostnaam die naar een intern
 * IP-adres wijst, of een korte clustervorm als {@code service.namespace}, komt erdoor. https-only
 * beperkt de schade, omdat het doel een geldig certificaat voor zijn hostnaam moet tonen; echte
 * bescherming hoort op het moment van verbinden (weiger private, loopback- en cluster-IP's, of een
 * egress-allowlist).
 * <p>
 * Een bean, zodat een test een lokale http-ontvanger kan toelaten.
 */
@ApplicationScoped
public class WebhookUrlValidator {

    // Gelijk aan de kolom webhook_url (varchar(2048)).
    private static final int MAX_LENGTE = 2048;
    private static final List<String> INTERNE_SUFFIXEN = List.of(
            ".localhost", ".local", ".internal", ".svc",
            ".home.arpa", ".home", ".corp", ".lan", ".intranet");

    /**
     * Controleert de URL uit het register en geeft hem genormaliseerd terug.
     *
     * @throws OngeldigeWebhookUrlException als de URL niet te ontleden is of niet door de controle komt
     */
    public String controleer(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new OngeldigeWebhookUrlException("de URL is niet te ontleden");
        }

        valideer(uri);

        return normaliseer(uri);
    }

    /**
     * @throws OngeldigeWebhookUrlException als de URL een van de controles hierboven niet haalt
     */
    public void valideer(URI webhookUrl) {
        if (webhookUrl == null) {
            return;
        }

        String url = webhookUrl.toString();

        if (url.length() > MAX_LENGTE) {
            throw new OngeldigeWebhookUrlException("de URL is langer dan " + MAX_LENGTE + " tekens");
        }

        if (!"https".equalsIgnoreCase(webhookUrl.getScheme())) {
            throw new OngeldigeWebhookUrlException("alleen een absolute https-URL is toegestaan");
        }

        if (webhookUrl.getUserInfo() != null) {
            throw new OngeldigeWebhookUrlException("gebruikersinfo (gebruiker@host) is niet toegestaan");
        }

        String host = webhookUrl.getHost();

        if (host == null) {
            throw new OngeldigeWebhookUrlException("de URL bevat geen geldige hostnaam");
        }

        valideerHost(host.toLowerCase(Locale.ROOT));
        int poort = webhookUrl.getPort();

        if (poort != -1 && (poort < 1 || poort > 65535)) {
            throw new OngeldigeWebhookUrlException("de poort valt buiten het bereik 1-65535");
        }
    }

    /**
     * De URL met het schema in kleine letters, of null zonder URL. De uitgaande REST-client vergelijkt
     * het schema hoofdlettergevoelig en zou een "HTTPS"-URL over platte HTTP sturen. Een URI zonder
     * schema komt ongewijzigd terug.
     *
     * @param webhookUrl een URL die door {@link #valideer(URI)} is gekomen
     */
    public String normaliseer(URI webhookUrl) {
        if (webhookUrl == null) {
            return null;
        }

        String url = webhookUrl.toString();

        if (webhookUrl.getScheme() == null) {
            return url;
        }

        return "https" + url.substring(webhookUrl.getScheme().length());
    }

    private static void valideerHost(String host) {
        // URI.getHost() houdt de haken van een IPv6-literal.
        if (host.startsWith("[")) {
            throw new OngeldigeWebhookUrlException("een IP-adres als host is niet toegestaan");
        }

        String genormaliseerd = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        int laatstePunt = genormaliseerd.lastIndexOf('.');

        if (genormaliseerd.equals("localhost") || laatstePunt < 0) {
            // Een naam zonder punt is intern: localhost, een Kubernetes-servicenaam, een intranethost.
            throw new OngeldigeWebhookUrlException("een interne hostnaam is niet toegestaan");
        }

        String laatsteLabel = genormaliseerd.substring(laatstePunt + 1);

        // Een laatste label van alleen cijfers dekt IPv4 in dotted-quad-vorm. Korte en hexadecimale
        // vormen (127.1, 0x7f.0x1) krijgen van URI een lege host en zijn hierboven al geweigerd.
        if (laatsteLabel.chars().allMatch(Character::isDigit)) {
            throw new OngeldigeWebhookUrlException("een IP-adres als host is niet toegestaan");
        }

        for (String suffix : INTERNE_SUFFIXEN) {
            if (genormaliseerd.endsWith(suffix)) {
                throw new OngeldigeWebhookUrlException("een interne hostnaam is niet toegestaan");
            }
        }
    }
}
