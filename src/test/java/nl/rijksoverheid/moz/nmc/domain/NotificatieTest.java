package nl.rijksoverheid.moz.nmc.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificatieTest {

    @Test
    void constructor_zetStatusCreatedEnEersteGeschiedenisRecord() {
        Notificatie notificatie = new Notificatie(null);

        assertEquals(StatusWaarde.CREATED, notificatie.getStatus());
        assertEquals(1, notificatie.getStatusGeschiedenis().size());
        assertEquals(StatusWaarde.CREATED, notificatie.getStatusGeschiedenis().get(0).status());
    }

    @Test
    void verwerkTerugmelding_meerdereWijzigingen_bouwtVolledigeGeschiedenisOp() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.markeerVerzonden(UUID.randomUUID());
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        assertEquals(3, geschiedenis.size());
        assertEquals(StatusWaarde.CREATED, geschiedenis.get(0).status());
        assertEquals(StatusWaarde.SENDING, geschiedenis.get(1).status());
        assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(2).status());
    }

    // getStatus() leest de kopie op Notificatie, niet de geschiedenis: bewaakt dat die het láátste
    // record volgt, niet per ongeluk het eerste.
    @Test
    void getStatus_retourneertLaatsteGeschiedenisRecordNietHetEerste() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.markeerVerzonden(UUID.randomUUID());
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);
        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(laatste.geregistreerd(), notificatie.getLaatsteStatusUpdate());
    }

    // De gebeurtenistijd komt van de bron (NotifyNL), de registratietijd van de eigen klok. Voor een
    // status die de NMC zelf vaststelt vallen ze samen; voor een delivery receipt niet.
    @Test
    void verwerkTerugmelding_metGebeurtenistijd_scheidtGebeurtenisVanRegistratie() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime opgetreden = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).truncatedTo(ChronoUnit.MICROS);

        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, opgetreden);

        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);
        assertEquals(opgetreden, laatste.tijdstip());
        assertTrue(laatste.geregistreerd().isAfter(opgetreden));
    }

    // De registratietijd komt van de eigen klok, niet van NotifyNL: een receipt met een oude
    // completed_at zet het moment van de laatste registratie niet terug.
    @Test
    void verwerkTerugmelding_metOudeGebeurtenistijd_zetDeRegistratietijdNietTerug() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime voorRegistratie = OffsetDateTime.now(ZoneOffset.UTC);

        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(40));

        assertFalse(notificatie.getLaatsteStatusUpdate().isBefore(voorRegistratie));
    }

    // De kopie volgt het laatst geregistreerde record, ook als de gebeurtenistijd ouder is dan die van
    // de vorige status; laatsteStatusUpdate volgt de eigen klok, niet die oudere gebeurtenistijd.
    @Test
    void verwerkTerugmelding_metOudereGebeurtenistijdDanDeHuidige_volgtDeProjectieDeLaatsteRegistratie() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.markeerVerzonden(UUID.randomUUID());
        OffsetDateTime oudereGebeurtenistijd = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).truncatedTo(ChronoUnit.MICROS);

        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, oudereGebeurtenistijd);

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(oudereGebeurtenistijd, notificatie.getStatusGeschiedenis().getLast().tijdstip());
        assertTrue(notificatie.getLaatsteStatusUpdate().isAfter(oudereGebeurtenistijd));
        assertEquals(3, notificatie.getStatusGeschiedenis().size());
    }

    // De projectie op Notificatie is een kopie van het laatste geschiedenisrecord: na elke
    // registratie moeten ze hetzelfde record teruggeven. Dat is hier binnen Java de enige bewaking —
    // in de database koppelt niets laatste_status aan de rij met het hoogste volgnummer. Zonder deze
    // test zou een registratiepad dat de projectie vergeet bij te werken pas opvallen in de code die
    // op de projectie stuurt, zoals verwerkTerugmelding.
    @Test
    void verwerkTerugmelding_naElkeWijziging_blijftDeProjectieGelijkAanDeGeschiedenis() {
        Notificatie notificatie = new Notificatie(null);
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.markeerVerzonden(UUID.randomUUID());
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.verwerkTerugmelding(StatusWaarde.TEMPORARY_FAILURE, null);
        bevestigProjectieVolgtGeschiedenis(notificatie);

        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);
        bevestigProjectieVolgtGeschiedenis(notificatie);
    }

    // getAangemaakt() is afgeleid van het eerste geschiedenisrecord: bewaakt dat dat echt het eerste
    // (index 0) record is, niet per ongeluk het laatste (waar de projectie op leunt).
    @Test
    void getAangemaakt_retourneertEersteGeschiedenisRecordNietHetLaatste() {
        Notificatie notificatie = new Notificatie(null);
        OffsetDateTime aanmaakTijdstip = notificatie.getStatusGeschiedenis().get(0).tijdstip();

        notificatie.markeerVerzonden(UUID.randomUUID());
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        assertEquals(aanmaakTijdstip, notificatie.getAangemaakt());
    }

    // markeerVerzonden doet de twee dingen die altijd samen horen: koppelen aan NotifyNL en SENDING
    // vastleggen. Als losse setter kon een van beide overgeslagen worden.
    @Test
    void markeerVerzonden_koppeltDeReferentieEnLegtSendingVast() {
        Notificatie notificatie = new Notificatie(null);
        UUID referentie = UUID.randomUUID();

        notificatie.markeerVerzonden(referentie);

        assertEquals(referentie, notificatie.getExternalReference());
        assertEquals(StatusWaarde.SENDING, notificatie.getStatus());
    }

    // Een tweede koppeling zou de eerste overschrijven, waarna de delivery receipts van die eerste
    // verzending nergens meer thuishoren: findByExternalReference vindt de notificatie dan niet meer.
    @Test
    void markeerVerzonden_tweeKeer_weigertDeTweede() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.markeerVerzonden(UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> notificatie.markeerVerzonden(UUID.randomUUID()));
    }

    // Na een terugmelding is de verzendfase voorbij; SENDING zou de uitkomst overschrijven.
    @Test
    void markeerVerzonden_naEenTerugmelding_weigert() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        assertThrows(IllegalStateException.class, () -> notificatie.markeerVerzonden(UUID.randomUUID()));
        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
    }

    @Test
    void verwerkTerugmelding_herhalingVanDeHuidigeStatus_legtNietsVast() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.markeerVerzonden(UUID.randomUUID());
        assertTrue(notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null));

        assertFalse(notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null));

        assertEquals(3, notificatie.getStatusGeschiedenis().size());
    }

    @Test
    void verwerkTerugmelding_teruggangNaarDeVerzendfase_legtNietsVast() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.markeerVerzonden(UUID.randomUUID());
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        assertFalse(notificatie.verwerkTerugmelding(StatusWaarde.SENDING, null));
        assertFalse(notificatie.verwerkTerugmelding(StatusWaarde.CREATED, null));

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(3, notificatie.getStatusGeschiedenis().size());
    }

    // Elke nieuwe melding wordt doorgegeven, ook als die status eerder al voorbijkwam.
    @Test
    void verwerkTerugmelding_eerdereStatusDieTerugkomt_wordtVastgelegd() {
        Notificatie notificatie = new Notificatie(null);
        notificatie.markeerVerzonden(UUID.randomUUID());
        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);
        notificatie.verwerkTerugmelding(StatusWaarde.PERMANENT_FAILURE, null);

        assertTrue(notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null));

        assertEquals(StatusWaarde.DELIVERED, notificatie.getStatus());
        assertEquals(5, notificatie.getStatusGeschiedenis().size());
    }

    @Test
    void verwerkTerugmelding_zonderGebeurtenistijd_valtTerugOpDeEigenKlok() {
        Notificatie notificatie = new Notificatie(null);

        notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);

        assertEquals(notificatie.getLaatsteStatusUpdate(), notificatie.getStatusGeschiedenis().getLast().tijdstip());
    }

    @Test
    void markeerVerzonden_zonderReferentie_weigert() {
        Notificatie notificatie = new Notificatie(null);

        assertThrows(NullPointerException.class, () -> notificatie.markeerVerzonden(null));
    }

    private static void bevestigProjectieVolgtGeschiedenis(Notificatie notificatie) {
        // De geschiedenis is geordend op registratievolgorde (volgnummer), dus het laatste element is
        // de laatste registratie — precies wat de kopie hoort te volgen.
        List<NotificatieStatus> geschiedenis = notificatie.getStatusGeschiedenis();
        NotificatieStatus laatste = geschiedenis.get(geschiedenis.size() - 1);

        assertEquals(laatste.status(), notificatie.getStatus());
        assertEquals(laatste.geregistreerd(), notificatie.getLaatsteStatusUpdate());
    }
}
