package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bewijst dat de retentiejob écht bij de Quarkus-scheduler geregistreerd staat onder de
 * geconfigureerde cron ({@code notificatie.retentie.cron}) — de rest van de suite roept
 * verwijderVerlopenNotificaties() rechtstreeks aan en omzeilt daarmee de @Scheduled/cron-koppeling
 * zelf. Laat de job bewust nooit echt vuren (draait in de gedeelde testcontext, met de
 * productie-cron van 03:00 's nachts): een kort-cyclische testcron die de job wél laat vuren, kan
 * bij het afsluiten van de testklasse een transactie laten weglekken naar andere @QuarkusTest's.
 */
@QuarkusTest
class NotificatieRetentieCronWiringTest {

    @Inject
    Scheduler scheduler;

    @Test
    void retentieTriggerIsGeregistreerdMetEenGeplandeVolgendeVuring() {
        Trigger trigger = scheduler.getScheduledJob("notificatie-retentie");

        assertNotNull(trigger, "Geen trigger geregistreerd voor identity 'notificatie-retentie' — "
                + "cron-property-resolutie of @Scheduled-registratie is stuk");
        assertNotNull(trigger.getNextFireTime(), "Trigger heeft geen geplande volgende vuring");
    }
}
