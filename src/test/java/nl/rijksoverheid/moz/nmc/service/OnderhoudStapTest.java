package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import nl.rijksoverheid.moz.nmc.domain.NotificatieStatus;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Het partitiebeheer faalt hier; de stappen daarna lopen door.
@QuarkusTest
class OnderhoudStapTest {

    @InjectMock
    Partitiebeheer partitiebeheer;

    @Inject
    OnderhoudTaakHandler onderhoudTaakHandler;

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    TaakRepository taakRepository;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            taakRepository.deleteAll();
            notificatieRepository.deleteAll();
        });
    }

    @Test
    void voerUit_stapFaalt_voertDeVolgendeStappenUitEnPlantZichzelfOpnieuw() {
        when(partitiebeheer.maakVolgendeAan()).thenThrow(new IllegalStateException("lock timeout"));
        when(partitiebeheer.ruimOp(any())).thenThrow(new IllegalStateException("lock timeout"));
        UUID verlopen = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.voegNotificatieToe(entityManager, verlopen,
                NotificatieStatus.GEANNULEERD, OffsetDateTime.now(ZoneOffset.UTC).minusDays(400)));

        TaakUitkomst uitkomst = onderhoudTaakHandler.voerUit(taak(), () -> {
        });

        assertNull(QuarkusTransaction.requiringNew().call(() -> notificatieRepository.findById(verlopen)));
        TaakUitkomst.Herpland herpland = assertInstanceOf(TaakUitkomst.Herpland.class, uitkomst);
        assertTrue(herpland.due().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(59)), "na nmc.onderhoud.interval");
    }

    // Een verloren lease is geen fout in een stap: de ronde stopt en de worker laat de taak aan de
    // nieuwe eigenaar.
    @Test
    void voerUit_leaseVerloren_stoptDeRonde() {
        Taak taak = taak();
        when(partitiebeheer.maakVolgendeAan()).thenThrow(new TaakVerlorenException(taak));

        assertThrows(TaakVerlorenException.class, () -> onderhoudTaakHandler.voerUit(taak, () -> {
        }));
    }

    @Test
    void constructor_ongeldigeBatch_weigert() {
        Bewaartermijnen termijnen = new Bewaartermijnen(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3));

        assertThrows(IllegalStateException.class,
                () -> new OnderhoudTaakHandler(null, null, null, termijnen, null, Duration.ofHours(1), 0, 1));
        assertThrows(IllegalStateException.class,
                () -> new OnderhoudTaakHandler(null, null, null, termijnen, null, Duration.ofHours(1), 1, 0));
        assertThrows(IllegalStateException.class,
                () -> new Partitiebeheer(null, termijnen, 0, 0, Duration.ofSeconds(1), 1));
        assertThrows(IllegalStateException.class,
                () -> new Partitiebeheer(null, termijnen, 10, -1, Duration.ofSeconds(1), 1));
    }

    private static Taak taak() {
        return new Taak(TaakSoort.ONDERHOUD, null, null, OffsetDateTime.now(ZoneOffset.UTC), null, null);
    }
}
