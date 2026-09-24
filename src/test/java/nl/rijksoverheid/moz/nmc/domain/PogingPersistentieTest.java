package nl.rijksoverheid.moz.nmc.domain;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.PogingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PogingPersistentieTest {

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    PogingRepository pogingRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            pogingRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void poging_naHerladen_behoudtVeldenEnLegeDuplicaatlijst() {
        UUID notificatieId = nieuweNotificatie();
        UUID pogingId = QuarkusTransaction.requiringNew().call(() -> {
            Poging poging = new Poging(notificatieId, 1);
            pogingRepository.persist(poging);

            return poging.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Poging herladen = pogingRepository.findById(pogingId);

            assertEquals(notificatieId, herladen.getNotificatieId());
            assertEquals(1, herladen.getNummer());
            assertEquals(PogingStatus.GEPLAND, herladen.getStatus());
            assertTrue(herladen.getDuplicaatIds().isEmpty());
            assertNull(herladen.getNotifyId());
            assertNull(herladen.getVerzondenOp());
            assertNull(herladen.getReceiptTijdstip());
        });
    }

    @Test
    void poging_tweedePogingMetHetzelfdeNummer_wordtGeweigerd() {
        UUID notificatieId = nieuweNotificatie();
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.persist(new Poging(notificatieId, 1)));

        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            pogingRepository.persist(new Poging(notificatieId, 1));
            pogingRepository.flush();
        }));
    }

    @Test
    void poging_bijVerwijderenVanDeNotificatie_verdwijntMee() {
        UUID notificatieId = nieuweNotificatie();
        QuarkusTransaction.requiringNew().run(() -> pogingRepository.persist(new Poging(notificatieId, 1)));

        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.deleteById(notificatieId));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> pogingRepository.count()));
    }

    private UUID nieuweNotificatie() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });
    }
}
