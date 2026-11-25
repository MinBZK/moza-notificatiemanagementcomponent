package nl.rijksoverheid.moz.nmc.migratie;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Notificatie_;
import nl.rijksoverheid.moz.nmc.domain.PogingStatus;
import nl.rijksoverheid.moz.nmc.domain.Reden;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * De enums die de database met een {@code CHECK}-constraint of een partitie kent. Een nieuwe constante
 * zonder migratie valt anders pas op als de applicatie hem in productie wegschrijft.
 */
@QuarkusTest
class EnumPariteitTest {

    private static final OffsetDateTime NU = OffsetDateTime.now(ZoneOffset.UTC);

    @Inject
    EntityManager entityManager;

    @Inject
    TaakRepository taakRepository;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @ParameterizedTest
    @EnumSource(NotificatieStatus.class)
    void notificatieStatus_elkeWaarde_isOpTeSlaan(NotificatieStatus status) {
        UUID id = UUID.randomUUID();

        QuarkusTransaction.requiringNew()
                .run(() -> NotificatieFixtures.voegNotificatieToe(entityManager, id, status, null));

        assertEquals(status, LeesHelper.notificatie(id).getStatus());
    }

    @ParameterizedTest
    @EnumSource(Reden.class)
    void reden_elkeWaarde_isOpTeSlaan(Reden reden) {
        UUID id = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.voegNotificatieToe(entityManager, id, NotificatieStatus.TECHNISCH_MISLUKT, null);
            entityManager
                    .createQuery("UPDATE Notificatie SET " + Notificatie_.REDEN + " = :reden WHERE " + Notificatie_.ID
                            + " = :id")
                    .setParameter("reden", reden)
                    .setParameter("id", id)
                    .executeUpdate();
        });

        assertEquals(reden, LeesHelper.notificatie(id).getReden());
    }

    @ParameterizedTest
    @EnumSource(PogingStatus.class)
    void pogingStatus_elkeWaarde_isOpTeSlaan(PogingStatus status) {
        UUID id = UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            NotificatieFixtures.voegNotificatieToe(entityManager, id, NotificatieStatus.VERZONDEN, null);
            NotificatieFixtures.voegPogingToe(entityManager, id, 1, UUID.randomUUID(), status, NU);
        });

        assertEquals(status, LeesHelper.laatstePoging(id).getStatus());
    }

    @ParameterizedTest
    @EnumSource(TaakSoort.class)
    void taakSoort_elkeWaarde_isOpTeSlaan(TaakSoort soort) {
        Taak taak = new Taak(soort, NotificatieFixtures.DV_ID, null, NU, null, null);

        QuarkusTransaction.requiringNew().run(() -> taakRepository.persist(taak));

        assertEquals(soort, LeesHelper.taak(soort, taak.getId()).orElseThrow().getTaakSoort());
    }

    // De takentabel is per soort gepartitioneerd. Een partitie zonder soort blijft leeg; een soort zonder
    // partitie is niet op te slaan.
    @Test
    void taakSoort_heeftPreciesEenPartitiePerWaarde() {
        @SuppressWarnings("unchecked")
        List<String> grenzen = QuarkusTransaction.requiringNew()
                .call(() -> entityManager
                        .createNativeQuery("SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i "
                                + "JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = 'taak'::regclass")
                        .getResultList());

        Set<String> verwacht = Arrays.stream(TaakSoort.values())
                .map(soort -> "FOR VALUES IN ('" + soort.name() + "')")
                .collect(Collectors.toSet());
        assertEquals(verwacht, Set.copyOf(grenzen));
        assertEquals(TaakSoort.values().length, grenzen.size(), "geen twee partities voor dezelfde soort");
    }
}
