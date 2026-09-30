package nl.rijksoverheid.moz.nmc.job;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Leest de instellingen van de retentiejob uit de configuratie. De controles staan in
 * {@link RetentieConfiguratie} zelf, zodat een test dezelfde weigeringen krijgt zonder configuratie.
 */
@ApplicationScoped
class RetentieConfiguratieProducer {

    // @Singleton en geen @ApplicationScoped: dat laatste is normal scoped en vraagt een proxy, wat
    // niet kan omdat een record final is.
    @Produces
    @Singleton
    RetentieConfiguratie retentieConfiguratie(
            @ConfigProperty(name = "notificatie.retentie.bewaartermijn") Duration bewaartermijn,
            @ConfigProperty(name = "notificatie.retentie.max-batches", defaultValue = "10000") int maxBatches) {
        return new RetentieConfiguratie(bewaartermijn, maxBatches);
    }
}
