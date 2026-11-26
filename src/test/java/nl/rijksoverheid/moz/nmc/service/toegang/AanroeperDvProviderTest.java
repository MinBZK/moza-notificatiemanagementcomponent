package nl.rijksoverheid.moz.nmc.service.toegang;

import nl.rijksoverheid.moz.nmc.testhelper.TestConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AanroeperDvProviderTest {

    private static final URI JWKS = URI.create("https://manager.example.nl/.well-known/jwks.json");

    @Test
    void huidigeDvId_controleUit_leestDeGeconfigureerdeUuid() {
        UUID geconfigureerd = UUID.randomUUID();
        Aanroeper aanroeper = new Aanroeper();
        aanroeper.zetDvId(UUID.randomUUID());

        assertEquals(geconfigureerd,
                new AanroeperDvProvider(TestConfig.fscUit(), aanroeper, " " + geconfigureerd + " ").huidigeDvId());
    }

    @Test
    void huidigeDvId_controleAan_leestDeDienstverlenerVanDeAanroep() {
        UUID uitToken = UUID.randomUUID();
        Aanroeper aanroeper = new Aanroeper();
        aanroeper.zetDvId(uitToken);

        assertEquals(uitToken,
                new AanroeperDvProvider(TestConfig.fsc(JWKS), aanroeper, UUID.randomUUID().toString()).huidigeDvId());
    }

    // Zonder vastgestelde dienstverlener mag de geconfigureerde er niet voor in de plaats komen.
    @Test
    void huidigeDvId_controleAanZonderVastgesteldeDienstverlener_gooitEenException() {
        AanroeperDvProvider provider =
                new AanroeperDvProvider(TestConfig.fsc(JWKS), new Aanroeper(), UUID.randomUUID().toString());

        assertThrows(IllegalStateException.class, provider::huidigeDvId);
    }

    @Test
    void constructor_geenUuid_weigertBijHetOpstarten() {
        assertThrows(IllegalStateException.class,
                () -> new AanroeperDvProvider(TestConfig.fscUit(), new Aanroeper(), "dienstverlener-1"));
    }
}
