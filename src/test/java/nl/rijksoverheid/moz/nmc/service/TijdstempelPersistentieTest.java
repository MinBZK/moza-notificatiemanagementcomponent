package nl.rijksoverheid.moz.nmc.service;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.Taak;
import nl.rijksoverheid.moz.nmc.domain.TaakSoort;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import nl.rijksoverheid.moz.nmc.testhelper.LeesHelper;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import nl.rijksoverheid.moz.nmc.testhelper.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * De database bewaart tijdstippen in microseconden en geeft ze in UTC terug. De entities brengen een
 * tijdstip al bij het aanmaken in die vorm, zodat een waarde vóór en na het opslaan gelijk vergelijkt.
 */
@QuarkusTest
class TijdstempelPersistentieTest {

    // Nanoseconden en een offset die geen UTC is.
    private static final OffsetDateTime INVOER = OffsetDateTime.parse("2030-06-15T14:30:45.123456789+02:00");
    private static final OffsetDateTime GENORMALISEERD = OffsetDateTime.of(2030, 6, 15, 12, 30, 45, 123_456_000, ZoneOffset.UTC);

    @Inject
    Overgangsfunctie overgangsfunctie;

    @Inject
    TaakRepository taakRepository;

    @BeforeEach
    void setUp() {
        TestDatabase.leeg();
    }

    @Test
    void notificatie_tijdstipMetNanosecondenEnOffset_isVoorEnNaOpslaanGelijk() {
        Notificatie notificatie = new Notificatie(NotificatieFixtures.DV_ID, INVOER);
        assertEquals(GENORMALISEERD, notificatie.getAangenomenOp());

        QuarkusTransaction.requiringNew().run(() -> overgangsfunctie.neemAan(notificatie));

        assertEquals(GENORMALISEERD, LeesHelper.notificatie(notificatie.getId()).getAangenomenOp());
    }

    @Test
    void taak_tijdstipMetNanosecondenEnOffset_isVoorEnNaOpslaanGelijk() {
        Taak taak = new Taak(TaakSoort.WISSEN, NotificatieFixtures.DV_ID, null, INVOER, null, null);
        assertEquals(GENORMALISEERD, taak.getDue());

        QuarkusTransaction.requiringNew().run(() -> taakRepository.persist(taak));

        assertEquals(GENORMALISEERD, LeesHelper.taak(TaakSoort.WISSEN, taak.getId()).orElseThrow().getDue());
    }
}
