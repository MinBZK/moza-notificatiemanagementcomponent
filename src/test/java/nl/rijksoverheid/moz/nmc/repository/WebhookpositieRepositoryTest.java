package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.Cursor;
import nl.rijksoverheid.moz.nmc.domain.Webhookpositie;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// geleverd_op is de leeftijd van de leverpositie waarop het opruimen van het eventlog let.
@QuarkusTest
class WebhookpositieRepositoryTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-01T10:00:00Z");
    private static final Cursor POSITIE = new Cursor(1, 100, 7);

    @Inject
    WebhookpositieRepository repository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery("DELETE FROM webhookpositie").executeUpdate());
    }

    @Test
    void bewaar_nogNietsGeleverd_laatGeleverdOpLeeg() {
        bewaar(Webhookpositie.nieuw(NotificatieFixtures.DV_ID), T1);

        assertNull(geleverdOp());
    }

    @Test
    void bewaar_mislukkingOpDezelfdePositie_laatGeleverdOpStaan() {
        bewaar(new Webhookpositie(NotificatieFixtures.DV_ID, POSITIE, 0, null), T1);

        bewaar(new Webhookpositie(NotificatieFixtures.DV_ID, POSITIE, 1, T1.plusHours(1)), T1.plusHours(1));

        assertEquals(T1.toInstant(), geleverdOp().toInstant());
    }

    @Test
    void bewaar_positieVerschoven_zetGeleverdOp() {
        bewaar(new Webhookpositie(NotificatieFixtures.DV_ID, POSITIE, 0, null), T1);

        bewaar(new Webhookpositie(NotificatieFixtures.DV_ID, new Cursor(1, 101, 8), 0, null), T1.plusHours(2));

        assertEquals(T1.plusHours(2).toInstant(), geleverdOp().toInstant());
    }

    private void bewaar(Webhookpositie positie, OffsetDateTime nu) {
        QuarkusTransaction.requiringNew().run(() -> repository.bewaar(positie, nu));
    }

    private OffsetDateTime geleverdOp() {
        return QuarkusTransaction.requiringNew().call(() -> (OffsetDateTime) entityManager
                .createNativeQuery("SELECT geleverd_op FROM webhookpositie WHERE dv_id = ?1", OffsetDateTime.class)
                .setParameter(1, NotificatieFixtures.DV_ID)
                .getSingleResult());
    }
}
