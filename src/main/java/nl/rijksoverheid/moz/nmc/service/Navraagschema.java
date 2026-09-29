package nl.rijksoverheid.moz.nmc.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Wanneer de afleverstatus van een poging wordt nagevraagd: op de geconfigureerde momenten na de
 * verzending, daarna dagelijks, en als laatste op het einde van de bewaartermijn van NotifyNL.
 */
@ApplicationScoped
public class Navraagschema {

    private static final Duration DAGELIJKS = Duration.ofDays(1);

    private final List<Duration> momenten;
    private final Duration bewaartermijn;

    public Navraagschema(@ConfigProperty(name = "nmc.navraag.momenten") List<Duration> momenten,
                         @ConfigProperty(name = "nmc.navraag.bewaartermijn-notifynl") Duration bewaartermijn) {
        if (momenten.isEmpty() || momenten.stream().anyMatch(m -> m.isNegative() || m.isZero())) {
            throw new IllegalStateException("nmc.navraag.momenten moet positieve duren bevatten, is " + momenten);
        }

        this.momenten = momenten.stream().sorted().toList();
        this.bewaartermijn = bewaartermijn;
    }

    /** Het eerste moment van navraag voor een poging die op {@code verzondenOp} bij NotifyNL ligt. */
    public OffsetDateTime eerste(OffsetDateTime verzondenOp) {
        return begrens(verzondenOp, verzondenOp.plus(momenten.getFirst()));
    }

    /**
     * Het volgende moment na {@code nu}.
     *
     * @return leeg als de bewaartermijn van NotifyNL om is en er niets meer na te vragen valt
     */
    public Optional<OffsetDateTime> volgende(OffsetDateTime verzondenOp, OffsetDateTime nu) {
        OffsetDateTime einde = verzondenOp.plus(bewaartermijn);

        if (!nu.isBefore(einde)) {
            return Optional.empty();
        }

        for (Duration moment : momenten) {
            if (verzondenOp.plus(moment).isAfter(nu)) {
                return Optional.of(begrens(verzondenOp, verzondenOp.plus(moment)));
            }
        }

        OffsetDateTime volgende = verzondenOp.plus(momenten.getLast());

        while (!volgende.isAfter(nu)) {
            volgende = volgende.plus(DAGELIJKS);
        }

        return Optional.of(begrens(verzondenOp, volgende));
    }

    private OffsetDateTime begrens(OffsetDateTime verzondenOp, OffsetDateTime moment) {
        OffsetDateTime einde = verzondenOp.plus(bewaartermijn);

        return moment.isAfter(einde) ? einde : moment;
    }
}
