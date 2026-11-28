package nl.rijksoverheid.moz.nmc.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Poging;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Een receipt noemt de poging via de reference; zonder bruikbare reference telt het NotifyNL-id.
@QuarkusTest
class PogingRepositoryTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-01T10:00:00Z");

    @Inject
    PogingRepository repository;

    @Inject
    EntityManager entityManager;

    private final UUID notificatieId = UUID.randomUUID();
    private final UUID notifyId = UUID.randomUUID();
    private UUID pogingId;

    @BeforeEach
    void setUp() {
        pogingId = QuarkusTransaction.requiringNew().call(() -> {
            NotificatieFixtures.voegNotificatieToe(entityManager, notificatieId, NotificatieStatus.VERZONDEN, null);
            NotificatieFixtures.voegPogingToe(entityManager, notificatieId, 1, notifyId, PogingStatus.VERZONDEN, T1);

            return NotificatieFixtures.pogingen(entityManager, notificatieId)
                    .getFirst()
                    .getId();
        });
    }

    @AfterEach
    void ruimOp() {
        QuarkusTransaction.requiringNew()
                .run(() -> NotificatieFixtures.verwijderNotificatie(entityManager, notificatieId));
    }

    @Test
    void zoekVoorReceipt_referenceIsPogingId_vindtDiePogingOokBijEenAnderNotifyId() {
        assertEquals(Optional.of(pogingId), zoek(UUID.randomUUID(), pogingId.toString()));
        assertEquals(Optional.of(pogingId), zoek(null, " " + pogingId + " "));
    }

    @Test
    void zoekVoorReceipt_onbruikbareReference_valtTerugOpHetNotifyId() {
        assertEquals(Optional.of(pogingId), zoek(notifyId, null));
        assertEquals(Optional.of(pogingId), zoek(notifyId, " "));
        assertEquals(Optional.of(pogingId), zoek(notifyId, "geen-uuid"));
    }

    @Test
    void zoekVoorReceipt_onbekendeReference_valtTerugOpHetNotifyId() {
        assertEquals(Optional.of(pogingId), zoek(notifyId, UUID.randomUUID().toString()));
    }

    @Test
    void zoekVoorReceipt_zonderBruikbareReferenceEnZonderNotifyId_vindtNiets() {
        assertTrue(zoek(null, null).isEmpty());
        assertTrue(zoek(null, "geen-uuid").isEmpty());
        assertTrue(zoek(UUID.randomUUID(), null).isEmpty());
    }

    private Optional<UUID> zoek(UUID notifyId, String reference) {
        return QuarkusTransaction.requiringNew()
                .call(() -> repository.zoekVoorReceipt(notifyId, reference).map(Poging::getId));
    }
}
