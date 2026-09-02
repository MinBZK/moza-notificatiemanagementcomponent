package nl.rijksoverheid.moz.nmc.service;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Validates the caller-supplied callbackUrl before it is stored (#752). The NMC later
 * POSTs a statusupdate to this address, so the URL must not point at an internal
 * destination (SSRF). Accepted: an absolute https-URL to a public hostname, at most
 * {@value #MAX_LENGTE} characters. Rejected: other schemes, userinfo, IP-literal hosts
 * and internal hostnames (localhost, single-label names, *.localhost/*.local/*.internal/*.svc,
 * *.home.arpa/*.home/*.corp/*.lan/*.intranet).
 * <p>
 * The endpoints reach these rules through
 * {@link nl.rijksoverheid.moz.nmc.validation.ValidCallbackUrl} in the contract, not by
 * calling this class; they only call {@link #normaliseer(URI)} afterwards.
 * <p>
 * This is a shape-based denylist, so it does not catch every internal destination: a
 * hostname that merely resolves to an internal IP, or a short in-cluster form such as
 * {@code service.namespace}, still passes. https-only limits the damage (the target must
 * present a valid certificate for its hostname), but full SSRF protection belongs at
 * connect time (resolve-and-refuse private/loopback/cluster IPs, or an egress allowlist);
 * follow-up: MinBZK/MijnOverheidZakelijk#1049.
 */
public final class CallbackUrlValidator {

    // Matches the callback_url column (varchar(2048)) so a long URL fails as 400, not at insert.
    private static final int MAX_LENGTE = 2048;
    private static final List<String> INTERNE_SUFFIXEN = List.of(
            ".localhost", ".local", ".internal", ".svc",
            ".home.arpa", ".home", ".corp", ".lan", ".intranet");

    private CallbackUrlValidator() {
    }

    /**
     * @throws OngeldigeCallbackUrlException when the URL fails one of the checks above
     */
    public static void valideer(URI callbackUrl) {
        if (callbackUrl == null) {
            return;
        }
        String url = callbackUrl.toString();
        if (url.length() > MAX_LENGTE) {
            throw new OngeldigeCallbackUrlException("de URL is langer dan " + MAX_LENGTE + " tekens");
        }
        if (!"https".equalsIgnoreCase(callbackUrl.getScheme())) {
            throw new OngeldigeCallbackUrlException("alleen een absolute https-URL is toegestaan");
        }
        if (callbackUrl.getUserInfo() != null) {
            throw new OngeldigeCallbackUrlException("gebruikersinfo (gebruiker@host) is niet toegestaan");
        }
        String host = callbackUrl.getHost();
        if (host == null) {
            throw new OngeldigeCallbackUrlException("de URL bevat geen geldige hostnaam");
        }
        valideerHost(host.toLowerCase(Locale.ROOT));
        int poort = callbackUrl.getPort();
        if (poort != -1 && (poort < 1 || poort > 65535)) {
            throw new OngeldigeCallbackUrlException("de poort valt buiten het bereik 1-65535");
        }
    }

    /**
     * Returns the URL with a lowercase scheme, or null when no callbackUrl was supplied.
     * The outbound REST client compares the scheme case-sensitively and would send an
     * "HTTPS" URL over plaintext HTTP.
     * A URI without a scheme is returned unchanged.
     *
     * @param callbackUrl a URL that passed {@link #valideer(URI)}
     */
    public static String normaliseer(URI callbackUrl) {
        if (callbackUrl == null) {
            return null;
        }
        String url = callbackUrl.toString();
        if (callbackUrl.getScheme() == null) {
            return url;
        }
        return "https" + url.substring(callbackUrl.getScheme().length());
    }

    private static void valideerHost(String host) {
        // URI.getHost() keeps the brackets of an IPv6 literal.
        if (host.startsWith("[")) {
            throw new OngeldigeCallbackUrlException("een IP-adres als host is niet toegestaan");
        }
        String genormaliseerd = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        int laatstePunt = genormaliseerd.lastIndexOf('.');
        if (genormaliseerd.equals("localhost") || laatstePunt < 0) {
            // Single-label hosts are internal names (localhost, Kubernetes service names, intranet hosts).
            throw new OngeldigeCallbackUrlException("een interne hostnaam is niet toegestaan");
        }
        String laatsteLabel = genormaliseerd.substring(laatstePunt + 1);
        // Rejects a host whose last label is all digits, which covers dotted-quad IPv4
        // literals. Short and hex forms (127.1, 0x7f.0x1) get a null host from URI and
        // are already rejected above.
        if (laatsteLabel.chars().allMatch(Character::isDigit)) {
            throw new OngeldigeCallbackUrlException("een IP-adres als host is niet toegestaan");
        }
        for (String suffix : INTERNE_SUFFIXEN) {
            if (genormaliseerd.endsWith(suffix)) {
                throw new OngeldigeCallbackUrlException("een interne hostnaam is niet toegestaan");
            }
        }
    }
}
