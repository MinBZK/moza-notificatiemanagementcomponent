package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    // Gebruikt een expliciet, ver in de toekomst liggend tijdstip (i.p.v. terug te vallen op
    // opeenvolgende now()-aanroepen) zodat de vergelijking deterministisch is: twee now()-aanroepen
    // kunnen in dezelfde kloktik vallen, waardoor een isBefore/isAfter-check — of een vergelijking
    // tegen "de vorige now()" — ook zou slagen als registreerStatus het veld niet meer bijwerkte.
    // Het tijdstip wordt via reflectie meegegeven aan de private overload: die blijft private omdat
    // er geen productiereden is om hem breder te openen, alleen een testbehoefte.
    @Test
    void registreerStatus_laatsteStatusUpdateBlijftGelijkAanLaatsteGeschiedenisRecord() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime explicietTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

        registreerStatusOp(notificatie, StatusWaarde.SENDING, explicietTijdstip);

        assertEquals(explicietTijdstip, notificatie.getLaatsteStatusUpdate());
        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        assertEquals(explicietTijdstip, geschiedenis.get(geschiedenis.size() - 1).tijdstip());
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

    private static void registreerStatusOp(Notificatie notificatie, StatusWaarde status, OffsetDateTime tijdstip) {
        try {
            Method methode = Notificatie.class.getDeclaredMethod("registreerStatus", StatusWaarde.class, OffsetDateTime.class);
            methode.setAccessible(true);
            methode.invoke(notificatie, status, tijdstip);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
