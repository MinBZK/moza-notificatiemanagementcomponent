package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Deze beans controleren hun configuratie in de constructor; zonder @Startup gebeurt dat pas bij het
// eerste gebruik, terwijl de applicatie al gereed meldt.
class ConfiguratieBijOpstartenTest {

    @ParameterizedTest
    @ValueSource(classes = {TaakClaimer.class, Verzendbudget.class, Navraagschema.class, Bewaartermijnen.class, Partitiebeheer.class})
    void beanMetConfiguratiecontrole_wordtBijHetOpstartenGemaakt(Class<?> bean) {
        assertTrue(bean.isAnnotationPresent(Startup.class), bean.getSimpleName() + " mist @Startup");
    }
}
