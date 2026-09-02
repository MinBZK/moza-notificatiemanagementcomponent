package nl.rijksoverheid.moz.nmc.job;

import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

// Alleen de constructorvalidatie: verwijderVerlopenNotificaties() zelf gebruikt sinds de
// batchgewijze verwijdering QuarkusTransaction.requiringNew(), wat een actieve Arc-container
// vereist en dus niet in een kale Mockito-test werkt. Dat gedrag wordt gedekt door
// NotificatieRetentieSchedulerTest (@QuarkusTest, echte database).
class NotificatieRetentieSchedulerUnitTest {

    private final NotificatieRepository notificatieRepository = mock(NotificatieRepository.class);

    @Test
    void constructor_negatieveBewaartermijn_gooitIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(-1)));
    }

    @Test
    void constructor_nulBewaartermijn_gooitIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler(notificatieRepository, Duration.ZERO));
    }
}
