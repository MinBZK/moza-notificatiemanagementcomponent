package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import nl.rijksoverheid.moz.nmc.config.ConfigControle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Deze beans controleren hun configuratie in de constructor; zonder @Startup gebeurt dat pas bij het
// eerste gebruik, terwijl de applicatie al gereed meldt.
class ConfiguratieBijOpstartenTest {

    @ParameterizedTest
    @ValueSource(classes = {AannameService.class, TaakClaimer.class, Verzendbudget.class, NotifyNLNavraagmomenten.class,
            Verzendbeleid.class, Opruimtermijnen.class, EventlogPartitiebeheer.class, ConfigControle.class})
    void beanMetConfiguratiecontrole_wordtBijHetOpstartenGemaakt(Class<?> bean) {
        assertTrue(bean.isAnnotationPresent(Startup.class), bean.getSimpleName() + " mist @Startup");
    }
}
