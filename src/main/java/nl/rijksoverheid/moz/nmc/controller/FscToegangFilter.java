package nl.rijksoverheid.moz.nmc.controller;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.Provider;
import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.helper.Problems;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.service.toegang.Aanroeper;
import nl.rijksoverheid.moz.nmc.service.toegang.FscSleutelsOnbereikbaarException;
import nl.rijksoverheid.moz.nmc.service.toegang.FscTokenValidator;
import nl.rijksoverheid.moz.nmc.service.toegang.OngeldigFscTokenException;

import java.util.Optional;

/**
 * Laat een aanroep alleen door met een geldig FSC-toegangstoken van een dienstverlener uit het
 * register, en legt die dienstverlener vast voor de rest van de aanroep. Geldt voor elke controller
 * zonder {@link BuitenFsc}. Het antwoord zegt niet waarom een token is afgekeurd; dat staat in de log.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class FscToegangFilter implements ContainerRequestFilter {

    static final String HEADER = "Fsc-Authorization";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String TOKEN_VEREIST = "Een geldig FSC-toegangstoken in de header " + HEADER + " is vereist";

    private final FscTokenValidator validator;
    private final DienstverlenerRepository dienstverlenerRepository;
    private final Aanroeper aanroeper;
    private final ResourceInfo resourceInfo;

    // Quarkus maakt het filter aan voordat de configuratie van de draaiende applicatie er is. Daarom
    // geen FscConfig hier: de validator is een bean die pas bij de eerste aanroep ontstaat.
    public FscToegangFilter(FscTokenValidator validator, DienstverlenerRepository dienstverlenerRepository,
                            Aanroeper aanroeper, ResourceInfo resourceInfo) {
        this.validator = validator;
        this.dienstverlenerRepository = dienstverlenerRepository;
        this.aanroeper = aanroeper;
        this.resourceInfo = resourceInfo;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!validator.ingeschakeld() || resourceInfo.getResourceClass().isAnnotationPresent(BuitenFsc.class)) {
            return;
        }

        String header = requestContext.getHeaderString(HEADER);

        if (header == null || header.isBlank()) {
            Log.warnf("Aanroep geweigerd: geen %s-header", HEADER);
            throw Problems.unauthorized("Niet geautoriseerd", TOKEN_VEREIST);
        }

        String oin = oinUit(header.strip());
        Optional<Dienstverlener> dienstverlener = dienstverlenerRepository.zoekOpOin(oin);

        if (dienstverlener.isEmpty()) {
            Log.warnf("Aanroep geweigerd: OIN %s staat niet in het register", oin);
            throw Problems.forbidden("Geen toegang", "De organisatie uit het FSC-toegangstoken is niet aangesloten op het NMC");
        }

        aanroeper.zetDvId(dienstverlener.get().getId());
    }

    // FSC schrijft geen Bearer voor in deze header; een outway die hem toch meestuurt, wordt verdragen.
    private String oinUit(String header) {
        String token = header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()).strip() : header;

        try {
            return validator.valideer(token);
        } catch (OngeldigFscTokenException e) {
            Log.warnf("Aanroep geweigerd: ongeldig FSC-toegangstoken (%s)", e.getMessage());
            throw Problems.unauthorized("Niet geautoriseerd", TOKEN_VEREIST);
        } catch (FscSleutelsOnbereikbaarException e) {
            Log.error("Het FSC-toegangstoken is niet te controleren", e);
            throw Problems.serviceUnavailable("Tijdelijk niet beschikbaar",
                    "Het FSC-toegangstoken kan nu niet worden gecontroleerd; probeer het later opnieuw");
        }
    }
}
