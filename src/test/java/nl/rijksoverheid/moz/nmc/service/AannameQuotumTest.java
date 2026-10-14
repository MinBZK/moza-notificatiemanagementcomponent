package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Het standaardquotum komt uit de configuratie; AannameServiceTest draait zonder, dus hier zonder Quarkus.
class AannameQuotumTest {

    private static final UUID DV_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final DienstverlenerRepository dienstverlenerRepository = mock(DienstverlenerRepository.class);
    private final NotificatieRepository notificatieRepository = mock(NotificatieRepository.class);

    @Test
    void neemAan_geenQuotumInHetRegister_gebruiktHetStandaardquotum() {
        zetRegister(Optional.empty(), 2);

        QuotumOverschredenException fout = assertThrows(QuotumOverschredenException.class,
                () -> aannameService(Optional.of(2)).neemAan(opdracht()));

        assertEquals("Het quotum van 2 notificaties per dag is bereikt", fout.getMessage());
    }

    @Test
    void neemAan_quotumInHetRegister_gaatVoorHetStandaardquotum() {
        zetRegister(Optional.of(1), 1);

        QuotumOverschredenException fout = assertThrows(QuotumOverschredenException.class,
                () -> aannameService(Optional.of(100)).neemAan(opdracht()));

        assertEquals("Het quotum van 1 notificaties per dag is bereikt", fout.getMessage());
    }

    @Test
    void standaardquotumNul_laatDeApplicatieNietStarten() {
        assertThrows(IllegalStateException.class, () -> aannameService(Optional.of(0)));
    }

    private void zetRegister(Optional<Integer> quotum, long vandaagAangenomen) {
        Dienstverlener dienstverlener = mock(Dienstverlener.class);
        when(dienstverlener.getQuotumPerDag()).thenReturn(quotum);
        when(dienstverlenerRepository.findById(DV_ID)).thenReturn(dienstverlener);
        when(notificatieRepository.telAangenomenSinds(eq(DV_ID), any(OffsetDateTime.class))).thenReturn(vandaagAangenomen);
    }

    private AannameService aannameService(Optional<Integer> standaardQuotum) {
        return new AannameService(mock(Overgangsfunctie.class), mock(Sleutelbeheer.class), () -> DV_ID,
                dienstverlenerRepository, notificatieRepository, mock(TaakRepository.class), standaardQuotum);
    }

    private static AannameOpdracht opdracht() {
        return new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email("burger@example.nl"), null, null,
                "test-template-id", null);
    }
}
