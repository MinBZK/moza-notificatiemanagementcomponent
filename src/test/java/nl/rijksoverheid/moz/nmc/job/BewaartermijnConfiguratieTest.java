package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(BewaartermijnTestProfile.class)
class BewaartermijnConfiguratieTest {

    @Inject
    NotificatieRetentieScheduler scheduler;

    @Inject
    NotificatieRepository notificatieRepository;

    @Test
    void deTermijnUitDeConfiguratieBepaaltDeGrens() {
        Duration termijn = Duration.ofDays(BewaartermijnTestProfile.DAGEN);
        UUID netBinnen = maakNotificatie(termijn.minusDays(1));
        UUID netBuiten = maakNotificatie(termijn.plusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(netBinnen).isPresent(),
                    "binnen de termijn hoort te blijven staan");
            assertTrue(notificatieRepository.findByIdOptional(netBuiten).isEmpty(),
                    "buiten de termijn hoort verwijderd te zijn");
        });
    }

    private UUID maakNotificatie(Duration ouderdom) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.verzetLaatsteStatusUpdate(
                notificatieRepository.getEntityManager(), id, OffsetDateTime.now(ZoneOffset.UTC).minus(ouderdom)));

        return id;
    }
}
