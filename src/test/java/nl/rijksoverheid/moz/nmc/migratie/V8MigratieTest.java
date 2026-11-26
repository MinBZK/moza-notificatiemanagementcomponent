package nl.rijksoverheid.moz.nmc.migratie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.service.DvProvider;
import org.junit.jupiter.api.Test;

/** Het schema na V8, zoals elke @QuarkusTest het aantreft. */
@QuarkusTest
class V8MigratieTest {

    @Inject
    EntityManager entityManager;

    @Inject
    DvProvider dvProvider;

    @Test
    void dvId_opNotificatieEnEvent_isVerplicht() {
        assertEquals("NO", isNullable("notificatie", "dv_id"));
        assertEquals("NO", isNullable("event", "dv_id"));
    }

    @Test
    void geconfigureerdeDienstverlener_staatInDeTabel() {
        Number aantal = (Number) QuarkusTransaction.requiringNew()
                .call(() -> entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM dienstverlener WHERE id = ?1")
                        .setParameter(1, dvProvider.huidigeDvId())
                        .getSingleResult());

        assertEquals(1, aantal.intValue());
    }

    private String isNullable(String tabel, String kolom) {
        return (String) QuarkusTransaction.requiringNew()
                .call(() -> entityManager
                        .createNativeQuery("SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_name = ?1 AND column_name = ?2")
                        .setParameter(1, tabel)
                        .setParameter(2, kolom)
                        .getSingleResult());
    }
}
