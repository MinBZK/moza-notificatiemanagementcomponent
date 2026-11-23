package nl.rijksoverheid.moz.nmc.repository;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TaakFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Een open notificatie heeft een taak nodig; een mislukte taak telt alleen zolang de notificatie geldig is.
@QuarkusTest
class NotificatieRepositoryTest {

    private static final OffsetDateTime NU = OffsetDateTime.parse("2030-01-01T10:00:00Z");
    private static final List<NotificatieStatus> OPEN = List.of(NotificatieStatus.AANGENOMEN, NotificatieStatus.VERZONDEN);

    @Inject
    NotificatieRepository repository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() ->
                NotificatieFixtures.voegNotificatieToe(entityManager, id, NotificatieStatus.VERZONDEN, null));
    }

    @AfterEach
    void ruimOp() {
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verwijderNotificatie(entityManager, id));
    }

    @Test
    void isZonderTaak_openStatusZonderTaak_isWaar() {
        assertTrue(isZonderTaak(OPEN));
    }

    @Test
    void isZonderTaak_statusNietOpen_isOnwaar() {
        assertFalse(isZonderTaak(List.of(NotificatieStatus.AANGENOMEN)));
    }

    @Test
    void isZonderTaak_openTaak_isOnwaar() {
        plan();

        assertFalse(isZonderTaak(OPEN));
    }

    @Test
    void isZonderTaak_mislukteTaakZonderGeldigheid_isOnwaar() {
        UUID taakId = plan();
        QuarkusTransaction.requiringNew().run(() -> TaakFixtures.markeerMislukt(entityManager, TaakSoort.VERZENDEN, taakId));

        assertFalse(isZonderTaak(OPEN));
    }

    @Test
    void isZonderTaak_mislukteTaakBinnenDeGeldigheid_isOnwaarEnNaDeGeldigheidWaar() {
        UUID taakId = plan();
        QuarkusTransaction.requiringNew().run(() -> {
            TaakFixtures.markeerMislukt(entityManager, TaakSoort.VERZENDEN, taakId);
            NotificatieFixtures.verzetGeldigTot(entityManager, id, NU.plusSeconds(1));
        });

        assertFalse(isZonderTaak(OPEN));

        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verzetGeldigTot(entityManager, id, NU));

        assertTrue(isZonderTaak(OPEN));
    }

    private UUID plan() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Taak taak = new Taak(TaakSoort.VERZENDEN, NotificatieFixtures.DV_ID, id, NU, null, null);
            taakRepository.persist(taak);

            return taak.getId();
        });
    }

    private boolean isZonderTaak(List<NotificatieStatus> statussen) {
        return QuarkusTransaction.requiringNew().call(() -> repository.isZonderTaak(id, statussen, NU));
    }
}
