package nl.rijksoverheid.moz.nmc.job;

import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // Een operator die op de eigen NMC-package filtert, kan alleen zien dat de retentiejob mislukt
    // als de fout ook echt propageert. Deze test beschermt tegen een toekomstige wijziging die de
    // exception per ongeluk stilzwijgend zou vangen (de FailedExecution-observer in de scheduler
    // vangt dit in productie op, maar deze unit-test loopt niet via de Quarkus-scheduler-infra).
    @Test
    void verwijderVerlopenNotificaties_mislukteBulkDelete_propageertException() {
        NotificatieRetentieScheduler scheduler = new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(30));
        when(notificatieRepository.delete(anyString(), any(Object.class), any(Object.class)))
                .thenThrow(new RuntimeException("DB onbereikbaar"));

        assertThrows(RuntimeException.class, scheduler::verwijderVerlopenNotificaties);
    }

    // Pint de twee query-predicaten expliciet tegen elkaar af: een IN/NOT IN-verwisseling (of het
    // per ongeluk meegeven van een andere grens/statuslijst aan de count-aanroep) zou anders door
    // geen enkele test worden opgemerkt — beide aanroepen retourneren gewoon een getal, ongeacht of
    // de voorwaarde klopt.
    @Test
    void verwijderVerlopenNotificaties_gebruiktInVoorDeleteEnNotInVoorCount_metZelfdeGrensEnStatussen() {
        NotificatieRetentieScheduler scheduler = new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(30));
        when(notificatieRepository.delete(anyString(), any(Object.class), any(Object.class))).thenReturn(0L);
        when(notificatieRepository.count(anyString(), any(Object.class), any(Object.class))).thenReturn(0L);

        scheduler.verwijderVerlopenNotificaties();

        ArgumentCaptor<String> deleteQuery = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> deleteStatussen = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> deleteGrens = ArgumentCaptor.forClass(Object.class);
        verify(notificatieRepository).delete(deleteQuery.capture(), deleteStatussen.capture(), deleteGrens.capture());

        ArgumentCaptor<String> countQuery = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> countStatussen = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> countGrens = ArgumentCaptor.forClass(Object.class);
        verify(notificatieRepository).count(countQuery.capture(), countStatussen.capture(), countGrens.capture());

        assertTrue(deleteQuery.getValue().contains(" IN ?1"));
        assertFalse(deleteQuery.getValue().contains("NOT IN"));
        assertTrue(countQuery.getValue().contains("NOT IN ?1"));

        assertEquals(deleteStatussen.getValue(), countStatussen.getValue());
        assertEquals(deleteGrens.getValue(), countGrens.getValue());
    }
}
