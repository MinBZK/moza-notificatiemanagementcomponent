package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // Toetst niet alleen dát er een volgende vuring gepland staat, maar ook wélke: alleen "niet null"
    // zou elke syntactisch geldige cron accepteren, dus ook een typefout als "0 3 * * * ?" (elk uur op
    // :03). Het omrekenen naar Europe/Amsterdam dekt meteen de timeZone-instelling van @Scheduled: zou
    // die per ongeluk UTC zijn, dan valt de vuring in Amsterdamse tijd op 04:00 of 05:00 (afhankelijk
    // van zomertijd) en faalt de urenassertie. Er wordt bewust niet getoetst óp welke dag de vuring
    // valt — dat hangt af van het moment waarop de test draait en zou de test rond middernacht
    // wisselvallig maken.
    @Test
    void retentieTriggerIsGeregistreerdMetEenGeplandeVolgendeVuringOmDrieUurAmsterdamseTijd() {
        Trigger trigger = scheduler.getScheduledJob("notificatie-retentie");

        assertNotNull(trigger, "Geen trigger geregistreerd voor identity 'notificatie-retentie' — "
                + "cron-property-resolutie of @Scheduled-registratie is stuk");
        assertNotNull(trigger.getNextFireTime(), "Trigger heeft geen geplande volgende vuring");

        ZonedDateTime volgendeVuring = trigger.getNextFireTime().atZone(ZoneId.of("Europe/Amsterdam"));
        assertEquals(3, volgendeVuring.getHour(), "Volgende vuring niet om 03:00 Amsterdamse tijd: "
                + volgendeVuring + " — verkeerde cron of verkeerde timeZone");
        assertEquals(0, volgendeVuring.getMinute(), "Volgende vuring niet op de hele minuut 0: " + volgendeVuring);
        assertEquals(0, volgendeVuring.getSecond(), "Volgende vuring niet op seconde 0: " + volgendeVuring);
    }
}
