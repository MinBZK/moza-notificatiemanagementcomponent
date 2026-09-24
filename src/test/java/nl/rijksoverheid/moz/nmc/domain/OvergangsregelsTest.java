package nl.rijksoverheid.moz.nmc.domain;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OvergangsregelsTest {

    @Inject
    EntityManager entityManager;

    // De tabel in de database en de kopie in Java moeten exact dezelfde paren bevatten.
    @Test
    void alle_komtOvereenMetToegestaneOvergangInDeDatabase() {
        Set<String> inJava = new HashSet<>();
        Overgangsregels.alle().forEach((van, naar) -> naar.forEach(n -> inJava.add(van.name() + ">" + n.name())));

        @SuppressWarnings("unchecked")
        List<Object[]> rijen = QuarkusTransaction.requiringNew().call(() ->
                entityManager.createNativeQuery("SELECT van, naar FROM toegestane_overgang").getResultList());
        Set<String> inDatabase = new HashSet<>();
        rijen.forEach(rij -> inDatabase.add(rij[0] + ">" + rij[1]));

        assertEquals(inJava, inDatabase);
    }

    @Test
    void isToegestaan_terminaleStatus_heeftGeenUitgaandeOvergang() {
        for (NotificatieStatus naar : NotificatieStatus.values()) {
            assertFalse(Overgangsregels.isToegestaan(NotificatieStatus.DEFINITIEF_BEZORGD, naar));
            assertFalse(Overgangsregels.isToegestaan(NotificatieStatus.GEANNULEERD, naar));
        }
    }

    @Test
    void isToegestaan_herverzending_isToegestaan() {
        assertTrue(Overgangsregels.isToegestaan(NotificatieStatus.VERZONDEN, NotificatieStatus.VERZONDEN));
    }

    @Test
    void isToegestaan_leegVertrekpunt_isNietToegestaan() {
        assertFalse(Overgangsregels.isToegestaan(null, NotificatieStatus.AANGENOMEN));
    }
}
