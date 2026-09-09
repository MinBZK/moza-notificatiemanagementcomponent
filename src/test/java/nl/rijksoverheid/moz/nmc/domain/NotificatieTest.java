package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificatieTest {

    @Test
    void constructor_zetStatusCreatedEnEersteGeschiedenisRecord() {
        Notificatie notificatie = new Notificatie(null);

        assertEquals(StatusWaarde.CREATED, notificatie.getStatus());
        assertEquals(1, notificatie.getStatusGeschiedenis().size());
        assertEquals(StatusWaarde.CREATED, notificatie.getStatusGeschiedenis().get(0).status());
    }

    @Test
    void registreerStatus_meerdereWijzigingen_bouwtVolledigeGeschiedenisOp() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.registreerStatus(StatusWaarde.SENDING);
        notificatie.registreerStatus(StatusWaarde.DELIVERED);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        assertEquals(3, geschiedenis.size());
        assertEquals(StatusWaarde.CREATED, geschiedenis.get(0).status());
        assertEquals(StatusWaarde.SENDING, geschiedenis.get(1).status());
        assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(2).status());
    }

    // getStatus()/getLaatsteStatusUpdate() lezen de projectiekolommen, niet de geschiedenis: bewaakt
    // dat die het láátste record volgen, niet per ongeluk het eerste.
    @Test
    void getStatusEnGetLaatsteStatusUpdate_retourneertLaatsteGeschiedenisRecordNietHetEerste() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.registreerStatus(StatusWaarde.SENDING);
        notificatie.registreerStatus(StatusWaarde.DELIVERED);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);
        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(laatste.tijdstip(), notificatie.getLaatsteStatusUpdate());
    }

    // laatsteStatus/laatsteStatusUpdate zijn een projectie van de statusgeschiedenis, geen tweede
    // bron van waarheid: na elke registratie moeten ze exact het chronologisch laatste record
    // teruggeven. Zonder deze test zou een registratiepad dat de projectie vergeet bij te werken
    // (of ernaast gaat zitten) pas in de retentiejob opvallen, die er als enige op selecteert.
    @Test
    void registreerStatus_naElkeWijziging_blijftDeProjectieGelijkAanDeGeschiedenis() {
        Notificatie notificatie = new Notificatie(null);
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.registreerStatus(StatusWaarde.SENDING);
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.registreerStatus(StatusWaarde.TEMPORARY_FAILURE);
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.registreerStatus(StatusWaarde.DELIVERED);
        bevestigProjectieVolgtGeschiedenis(notificatie);
    }

    // getAangemaakt() is afgeleid van het eerste geschiedenisrecord: bewaakt dat dat echt het eerste
    // (index 0) record is, niet per ongeluk het laatste (waar getLaatsteStatusUpdate() op leunt).
    @Test
    void getAangemaakt_retourneertEersteGeschiedenisRecordNietHetLaatste() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime aanmaakTijdstip = notificatie.getStatusGeschiedenis().get(0).tijdstip();

        notificatie.registreerStatus(StatusWaarde.SENDING);
        notificatie.registreerStatus(StatusWaarde.DELIVERED);

        assertEquals(aanmaakTijdstip, notificatie.getAangemaakt());
    }

    private static void bevestigProjectieVolgtGeschiedenis(Notificatie notificatie) {
        // Zelfde regel als registreerStatus: bij een gelijk tijdstip telt de laatst geregistreerde.
        NotificatieStatus chronologischLaatste = notificatie.getStatusGeschiedenis().stream()
                .reduce((eerder, later) -> later.tijdstip().isBefore(eerder.tijdstip()) ? eerder : later)
                .orElseThrow();

        assertEquals(chronologischLaatste.status(), notificatie.getStatus());
        assertEquals(chronologischLaatste.tijdstip(), notificatie.getLaatsteStatusUpdate());
    }
}
