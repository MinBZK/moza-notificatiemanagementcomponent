package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

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

    // getStatus()/getLaatsteStatusUpdate() zijn afgeleid van het laatste geschiedenisrecord: bewaakt
    // dat dat echt het láátste (index size-1) record is, niet per ongeluk het eerste.
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
}
