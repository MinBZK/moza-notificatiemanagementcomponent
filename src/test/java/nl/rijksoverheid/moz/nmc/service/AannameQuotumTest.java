package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Dienstverlener;
import nl.rijksoverheid.moz.nmc.domain.Ontvanger;
import nl.rijksoverheid.moz.nmc.repository.DienstverlenerRepository;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.repository.TaakRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    void neemAan_teltVanafHetBeginVanDeDagInUtc() {
        zetRegister(Optional.of(1), 1);
        Clock vlakVoorMiddernacht = Clock.fixed(Instant.parse("2030-01-01T23:59:30Z"), ZoneOffset.UTC);

        assertThrows(QuotumOverschredenException.class,
                () -> aannameService(Optional.empty(), vlakVoorMiddernacht).neemAan(opdracht()));

        verify(notificatieRepository).telAangenomenSinds(DV_ID, OffsetDateTime.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void neemAan_dienstverlenerNietInHetRegister_weigert() {
        AannameService aannameService = aannameService(Optional.of(1));

        IllegalStateException fout = assertThrows(IllegalStateException.class,
                () -> aannameService.neemAan(opdracht()));

        assertEquals("Dienstverlener " + DV_ID + " staat niet in het register", fout.getMessage());
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
        return aannameService(standaardQuotum, Clock.systemUTC());
    }

    private AannameService aannameService(Optional<Integer> standaardQuotum, Clock clock) {
        return new AannameService(mock(Overgangsfunctie.class), mock(Sleutelbeheer.class), () -> DV_ID,
                dienstverlenerRepository, notificatieRepository, mock(TaakRepository.class), verzendbeleid(),
                clock, standaardQuotum);
    }

    private static Verzendbeleid verzendbeleid() {
        Verzendbeleid verzendbeleid = mock(Verzendbeleid.class);
        when(verzendbeleid.geldigheid(any())).thenReturn(Duration.ofDays(7));

        return verzendbeleid;
    }

    private static AannameOpdracht opdracht() {
        return new AannameOpdracht(Regie.DECENTRAAL, Ontvanger.email("burger@example.nl"), null, null,
                BerichtType.DEMO_TEMPLATE, null);
    }
}
