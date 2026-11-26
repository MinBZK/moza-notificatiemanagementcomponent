package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.runtime.Startup;
import nl.rijksoverheid.moz.nmc.config.ConfigValidatie;
import nl.rijksoverheid.moz.nmc.service.aanname.AannameService;
import nl.rijksoverheid.moz.nmc.service.onderhoud.EventlogPartitiebeheer;
import nl.rijksoverheid.moz.nmc.service.verzending.NotifyNLNavraagmomenten;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Deze beans controleren hun configuratie in de constructor; zonder @Startup gebeurt dat pas bij het
// eerste gebruik, terwijl de applicatie al gereed meldt.
@Tag("unit")
class ConfiguratieBijOpstartenTest {

    @ParameterizedTest
    @ValueSource(classes = {AannameService.class, NotifyNLNavraagmomenten.class, Verzendbeleid.class,
            EventlogPartitiebeheer.class, ConfigValidatie.class})
    void beanMetConfiguratiecontrole_wordtBijHetOpstartenGemaakt(Class<?> bean) {
        assertTrue(bean.isAnnotationPresent(Startup.class), bean.getSimpleName() + " mist @Startup");
    }
}
