package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificatieTest {

    @Test
    void constructor_zetStatusCreatedEnEersteGeschiedenisRecord() {
        Notificatie notificatie = new Notificatie(null);

        assertEquals(StatusWaarde.CREATED, notificatie.getStatus().status());
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

    // getStatus()/getLaatsteStatusTijdstip() lezen de projectiekolommen, niet de geschiedenis:
    // bewaakt dat die het láátste record volgen, niet per ongeluk het eerste.
    @Test
    void getStatusEnGetLaatsteStatusTijdstip_retourneertLaatsteGeschiedenisRecordNietHetEerste() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.registreerStatus(StatusWaarde.SENDING);
        notificatie.registreerStatus(StatusWaarde.DELIVERED);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);
        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus().status());
        assertEquals(laatste.tijdstip(), notificatie.getStatus().tijdstip());
    }

    // De gebeurtenistijd komt van de bron (NotifyNL), de registratietijd van de eigen klok. Voor een
    // status die de NMC zelf vaststelt vallen ze samen; voor een delivery receipt niet.
    @Test
    void registreerStatus_metGebeurtenistijd_scheidtGebeurtenisVanRegistratie() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime opgetreden = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);

        notificatie.registreerStatus(StatusWaarde.DELIVERED, opgetreden);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);
        assertEquals(opgetreden, laatste.tijdstip());
        assertTrue(laatste.geregistreerd().isAfter(opgetreden));
    }

    // De bewaartermijn vaart op laatsteStatusUpdate en dat is de eigen klok, niet die van NotifyNL.
    // Een receipt met een oude completed_at — bijvoorbeeld een herhaling, NotifyNL probeert tot 5x
    // met 5 minuten ertussen — mag een notificatie niet meteen opruimbaar maken: er is zojuist nog
    // iets over binnengekomen, dus ze is niet inactief.
    @Test
    void registreerStatus_metOudeGebeurtenistijd_zetDeBewaartermijnNietTerug() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime voorRegistratie = OffsetDateTime.now(ZoneOffset.UTC);

        notificatie.registreerStatus(StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(40));

        assertFalse(notificatie.getStatus().geregistreerd().isBefore(voorRegistratie));
    }

    // De projectie volgt het laatst geregistreerde record, ook als de gebeurtenistijd ouder is dan
    // die van de vorige status. Welke overgangen mogen wordt bepaald door StatusWaarde#volgtOp in
    // NotificatieService, niet door een tweede tijdcontrole hier: zou deze klasse een registratie
    // alsnog weigeren te projecteren, dan bevat de geschiedenis een status die laatsteStatus
    // tegenspreekt. De gebeurtenistijd komt van een externe klok en kan scheef zijn.
    @Test
    void registreerStatus_metOudereGebeurtenistijdDanDeHuidige_volgtDeProjectieDeLaatsteRegistratie() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.registreerStatus(StatusWaarde.SENDING);
        OffsetDateTime oudereGebeurtenistijd = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        notificatie.registreerStatus(StatusWaarde.DELIVERED, oudereGebeurtenistijd);

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus().status());
        assertEquals(oudereGebeurtenistijd, notificatie.getStatus().tijdstip());
        assertEquals(3, notificatie.getStatusGeschiedenis().size());
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
        // De geschiedenis is geordend op registratietijd, dus het laatste element is de laatste
        // registratie — precies wat de projectie hoort te volgen.
        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);

        assertEquals(laatste.status(), notificatie.getStatus().status());
        assertEquals(laatste.tijdstip(), notificatie.getStatus().tijdstip());
        assertEquals(laatste.geregistreerd(), notificatie.getStatus().geregistreerd());
    }
}
