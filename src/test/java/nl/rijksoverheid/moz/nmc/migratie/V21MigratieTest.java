package nl.rijksoverheid.moz.nmc.migratie;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class V21MigratieTest {

    @Inject
    EntityManager entityManager;

    // De cascade bij het verwijderen van een notificatie zoekt taken op notificatie_id alleen.
    @Test
    void taak_heeftEenVolledigeIndexOpNotificatieId() {
        String definitie = (String) QuarkusTransaction.requiringNew().call(() -> entityManager
                .createNativeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'taak' AND indexname = 'taak_notificatie_idx'")
                .getSingleResult());

        assertEquals("CREATE INDEX taak_notificatie_idx ON ONLY public.taak USING btree (notificatie_id)", definitie);
    }
}
