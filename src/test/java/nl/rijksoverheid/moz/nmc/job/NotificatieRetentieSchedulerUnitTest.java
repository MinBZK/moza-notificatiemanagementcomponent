package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.scheduler.FailedExecution;
import io.quarkus.scheduler.ScheduledExecution;
import io.quarkus.scheduler.SkippedExecution;
import io.quarkus.scheduler.Trigger;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// De constructorvalidatie van de scheduler, die van BatchResultaat en de twee @Observes-methodes:
// alle drie hebben ze geen database of transactie nodig. verwijderVerlopenNotificaties() zelf
// gebruikt sinds de batchgewijze verwijdering QuarkusTransaction.requiringNew(), wat een actieve
// Arc-container vereist en dus niet in een kale Mockito-test werkt. Dat gedrag wordt gedekt door
// NotificatieRetentieSchedulerTest (@QuarkusTest, echte database).
class NotificatieRetentieSchedulerUnitTest {

    private static final String RETENTIE_TRIGGER_ID = "notificatie-retentie";

    private final NotificatieRepository notificatieRepository = mock(NotificatieRepository.class);

    private final NotificatieRetentieScheduler scheduler =
            new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(30));

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

    @Test
    void batchResultaat_negatiefAantal_gooitIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler.BatchResultaat(-1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler.BatchResultaat(0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler.BatchResultaat(1, 0, -1));
    }

    // Bewaakt de invariant die de aggregaat-WARN-log ("x van de y kandidaten") betekenis geeft:
    // teller en noemer gaan over dezelfde populatie, dus de teller kan nooit groter zijn.
    @Test
    void batchResultaat_meerNietDefinitiefDanKandidaten_gooitIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificatieRetentieScheduler.BatchResultaat(2, 2, 3));
    }

    // Minder verwijderd dan kandidaten is juist een geldige uitkomst (een andere pod was eerder, of
    // een kandidaat is intussen niet meer verlopen) en mag dus niet afketsen op de validatie.
    @Test
    void batchResultaat_minderVerwijderdDanKandidaten_isGeldig() {
        NotificatieRetentieScheduler.BatchResultaat resultaat =
                new NotificatieRetentieScheduler.BatchResultaat(10, 4, 3);

        assertEquals(10, resultaat.kandidaten());
        assertEquals(4, resultaat.verwijderd());
        assertEquals(3, resultaat.nietDefinitiefKandidaten());
    }

    // De observers vuren voor élke @Scheduled-methode in de applicatie; zonder de filtering op
    // trigger-id zou een overgeslagen of mislukte run van een andere job hier als retentiejob gelogd
    // worden. Getoetst wordt of de guard de logregel écht kortsluit, niet of er precies die tekst
    // uitkomt: getDetail()/getException() worden alleen gelezen om de logregel te vullen, dus of ze
    // aangeroepen zijn is een waarneembare stand-in voor "er is wel/niet gelogd" — zonder dat er een
    // log-opvanginfrastructuur voor twee logregels opgetuigd hoeft te worden.
    @Test
    void opOvergeslagenUitvoering_voorDeRetentietrigger_leestDeOverslagredenUitVoorDeLogregel() {
        SkippedExecution event = overgeslagenUitvoeringVoor(RETENTIE_TRIGGER_ID);

        scheduler.opOvergeslagenUitvoering(event);

        verify(event).getDetail();
    }

    @Test
    void opOvergeslagenUitvoering_voorEenAndereTrigger_logtNiets() {
        SkippedExecution event = overgeslagenUitvoeringVoor("een-andere-job");

        scheduler.opOvergeslagenUitvoering(event);

        verify(event, never()).getDetail();
    }

    @Test
    void opMislukteUitvoering_voorDeRetentietrigger_leestDeFoutUitVoorDeLogregel() {
        FailedExecution event = mislukteUitvoeringVoor(RETENTIE_TRIGGER_ID);

        scheduler.opMislukteUitvoering(event);

        verify(event).getException();
    }

    @Test
    void opMislukteUitvoering_voorEenAndereTrigger_logtNiets() {
        FailedExecution event = mislukteUitvoeringVoor("een-andere-job");

        scheduler.opMislukteUitvoering(event);

        verify(event, never()).getException();
    }

    // SkippedExecution/FailedExecution worden gemockt in plaats van geconstrueerd, zodat de aanroepen
    // op de payload-getters te verifiëren zijn; alleen getExecution() hoeft gestubd te worden, want
    // dat is het enige wat de observers eruit halen naast die getters. De uitvoering wordt eerst in
    // een lokale variabele gebouwd: een when()-aanroep binnen een nog lopende when() ziet Mockito als
    // onvoltooide stubbing.
    private static SkippedExecution overgeslagenUitvoeringVoor(String triggerId) {
        ScheduledExecution uitvoering = uitvoeringVoor(triggerId);
        SkippedExecution event = mock(SkippedExecution.class);
        when(event.getExecution()).thenReturn(uitvoering);

        return event;
    }

    private static FailedExecution mislukteUitvoeringVoor(String triggerId) {
        ScheduledExecution uitvoering = uitvoeringVoor(triggerId);
        FailedExecution event = mock(FailedExecution.class);
        when(event.getExecution()).thenReturn(uitvoering);

        return event;
    }

    private static ScheduledExecution uitvoeringVoor(String triggerId) {
        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn(triggerId);
        ScheduledExecution uitvoering = mock(ScheduledExecution.class);
        when(uitvoering.getTrigger()).thenReturn(trigger);

        return uitvoering;
    }
}
