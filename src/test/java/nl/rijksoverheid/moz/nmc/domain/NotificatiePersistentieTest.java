package nl.rijksoverheid.moz.nmc.domain;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import nl.rijksoverheid.moz.nmc.testhelper.NotificatieFixtures;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persisteert en herlaadt een Notificatie in twee losse transacties: bewijst dat NotificatieStatus-
 * hydratatie, @OrderColumn-volgorde (registratievolgorde, kolom volgnummer) en de kopie in
 * getStatus() en getLaatsteStatusUpdate() ook standhouden na een echte round-trip door de database,
 * niet alleen in-memory (zie NotificatieTest).
 */
@QuarkusTest
class NotificatiePersistentieTest {

    @Inject
    NotificatieRepository notificatieRepository;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    @Test
    void notificatie_naHerladen_behoudtStatusGeschiedenisInVolgordeEnBlijftInvariantKloppen() {
        // Een ver uiteenliggende gebeurtenistijd voor de laatste overgang, zodat de assertie erop niet
        // toevallig op een eerder record past. Afgerond op microseconden: de kolom is timestamp(6), anders faalt de vergelijking met de
        // herladen (afgeronde) waarde op de nanoseconden die de database toch niet bewaart.
        OffsetDateTime laatsteTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.MICROS);

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.markeerVerzonden(UUID.randomUUID());
            notificatie.verwerkTerugmelding(StatusWaarde.TEMPORARY_FAILURE, null);
            notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, laatsteTijdstip);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            List<NotificatieStatus> geschiedenis = herladen.getStatusGeschiedenis();
            assertEquals(4, geschiedenis.size());
            assertEquals(StatusWaarde.CREATED, geschiedenis.get(0).status());
            assertEquals(StatusWaarde.SENDING, geschiedenis.get(1).status());
            assertEquals(StatusWaarde.TEMPORARY_FAILURE, geschiedenis.get(2).status());
            assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(3).status());
            assertEquals(laatsteTijdstip, geschiedenis.get(3).tijdstip());

            assertEquals(StatusWaarde.DELIVERED, herladen.getStatus());
            assertEquals(geschiedenis.get(3).geregistreerd(), herladen.getLaatsteStatusUpdate());
        });
    }

    // De gebeurtenistijden lopen hier bewust tegen de registratievolgorde in: DELIVERED krijgt een
    // latere completed_at dan PERMANENT_FAILURE, terwijl DELIVERED als eerste geregistreerd wordt.
    // Na herladen moet de geschiedenis nog steeds op registratievolgorde staan (@OrderColumn op
    // volgnummer) en moet de projectie de laatste registratie volgen, hier PERMANENT_FAILURE. Zou er op tijdstip geordend worden, dan zou een receipt met een scheve of oude
    // completed_at de volgorde omgooien en getAangemaakt() (dat getFirst() leest) de verkeerde rij
    // teruggeven.
    @Test
    void notificatie_metGebeurtenistijdenTegenDeRegistratievolgordeIn_herlaadtOpRegistratievolgorde() {
        OffsetDateTime deliveredTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime faalTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.MICROS);

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, deliveredTijdstip);
            notificatie.verwerkTerugmelding(StatusWaarde.PERMANENT_FAILURE, faalTijdstip);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            List<NotificatieStatus> geschiedenis = herladen.getStatusGeschiedenis();
            assertEquals(3, geschiedenis.size());
            assertEquals(StatusWaarde.CREATED, geschiedenis.get(0).status());
            assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(1).status());
            assertEquals(StatusWaarde.PERMANENT_FAILURE, geschiedenis.get(2).status());

            assertEquals(StatusWaarde.PERMANENT_FAILURE, herladen.getStatus());
            assertEquals(faalTijdstip, herladen.getStatusGeschiedenis().getLast().tijdstip());
        });
    }

    // De andere persistentietests kappen hun fixtures zelf al af op microseconden en gebruiken UTC;
    // hier gaat een waarde mét nanoseconden én een niet-UTC-offset door de database heen en terug.
    // Dit draait op H2, dat de offset bewaart: de precisiekant wordt volledig getoetst, de offsetkant
    // pas echt op PostgreSQL.
    @Test
    void notificatieStatus_metNanosecondenEnEenAndereOffset_komtGenormaliseerdTerug() {
        OffsetDateTime ruw = OffsetDateTime.parse("2026-03-01T14:00:00.123456789+02:00");
        OffsetDateTime verwacht = OffsetDateTime.parse("2026-03-01T12:00:00.123456Z");

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, ruw);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            assertEquals(verwacht, herladen.getStatusGeschiedenis().getLast().tijdstip());
            assertEquals(ZoneOffset.UTC, herladen.getStatusGeschiedenis().getLast().tijdstip().getOffset());
            assertEquals(ZoneOffset.UTC, herladen.getLaatsteStatusUpdate().getOffset());
        });
    }

    // V2 beschrijft de primary key (notificatie_id, volgnummer) als het vangnet onder
    // notificatie.versie: twee gelijktijdige callbacks die vanaf dezelfde toestand werken willen
    // allebei hetzelfde volgnummer schrijven, en de tweede hoort daarop stuk te lopen. Dat vangnet
    // gaat pas af als @Version ooit sneuvelt bij een refactor, dus geen enkele gewone test raakt het.
    // Hier wordt het rechtstreeks met SQL afgedwongen, zoals een schrijver die Hibernate omzeilt.
    @Test
    void notificatieStatus_metEenDubbelVolgnummer_wordtDoorDePrimaryKeyGeweigerd() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                        NotificatieFixtures.voegStatusregelToe(notificatieRepository.getEntityManager(), id, 0,
                                StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC))),
                "volgnummer 0 is al bezet door de CREATED uit de constructor");
    }

    // Bewaakt dat de CHECK-constraint op notificatie_status.status (V2__notificatie_statusgeschiedenis.sql)
    // elke StatusWaarde-constante toestaat. Zonder deze test zou een nieuwe of hernoemde constante
    // compileren en alle Java-tests laten slagen, maar pas bij de eerste echte INSERT in productie
    // op een constraint-violation stuiten. CREATED en SENDING staan er via constructor en
    // markeerVerzonden al in; voor die twee legt verwerkTerugmelding niets extra vast.
    @ParameterizedTest
    @EnumSource(StatusWaarde.class)
    void notificatieStatus_elkeStatusWaarde_voldoetAanDatabaseCheckConstraint(StatusWaarde status) {
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.markeerVerzonden(UUID.randomUUID());
            notificatie.verwerkTerugmelding(status, null);
            notificatieRepository.persist(notificatie);
            notificatieRepository.flush();
        });
    }

    // Een rij zoals de V2-backfill hem achterlaat: één geschiedenisrecord op volgnummer 0. Een
    // volgende status hoort op volgnummer 1 te landen, niet op de primary key te botsen.
    @Test
    void notificatieUitDeBackfill_krijgtEenVolgendeStatusErgensAchter() {
        UUID id = UUID.randomUUID();
        UUID referentie = UUID.randomUUID();
        OffsetDateTime aangemaakt = OffsetDateTime.parse("2026-01-15T10:00:00Z");
        QuarkusTransaction.requiringNew().run(() -> NotificatieFixtures.voegNotificatieMetEenStatusregelToe(
                notificatieRepository.getEntityManager(), id, referentie, StatusWaarde.SENDING, aangemaakt));

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findById(id).verwerkTerugmelding(StatusWaarde.DELIVERED, null)));

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            assertEquals(List.of(StatusWaarde.SENDING, StatusWaarde.DELIVERED),
                    herladen.getStatusGeschiedenis().stream().map(NotificatieStatus::status).toList());
            assertEquals(StatusWaarde.DELIVERED, herladen.getStatus());
            assertEquals(aangemaakt, herladen.getAangemaakt());
        });
    }

    // Een gat in volgnummer kan alleen buiten Hibernate om ontstaan; @OrderColumn laadt dan een null in
    // de lijst. Dat hoort een melding op te leveren die de oorzaak noemt, geen NullPointerException.
    @Test
    void notificatieMetEenOntbrekendVolgnummer_meldtDatDeGeschiedenisOnvolledigIs() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.markeerVerzonden(UUID.randomUUID());
            notificatie.verwerkTerugmelding(StatusWaarde.DELIVERED, null);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() ->
                NotificatieFixtures.verwijderStatusregel(notificatieRepository.getEntityManager(), id, 1));

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            IllegalStateException fout = assertThrows(IllegalStateException.class, herladen::getStatusGeschiedenis);

            assertTrue(fout.getMessage().contains("mist een volgnummer"), fout.getMessage());
        });
    }

    // Zonder @Version zouden twee gelijktijdige callbacks die dezelfde geschiedenis inlezen elkaars
    // statusregel geruisloos overschrijven. De test bootst dat na: een geneste requiringNew()-
    // transactie commit een eigen statusregel terwijl de buitenste de notificatie al had ingelezen.
    @Test
    void notificatie_tweeTransactiesWijzigenDezelfdeStatusGeschiedenis_laatDeTweedeCommitFalen() {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        Throwable fout = assertThrows(Throwable.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            Notificatie eerste = notificatieRepository.findById(id);
            // Dwingt de lazy @ElementCollection af binnen deze transactie, zodat de collectie écht
            // ingelezen is vóór de concurrent gecommitte wijziging hieronder.
            assertEquals(1, eerste.getStatusGeschiedenis().size());

            QuarkusTransaction.requiringNew().run(() -> {
                Notificatie tweede = notificatieRepository.findById(id);
                tweede.verwerkTerugmelding(StatusWaarde.DELIVERED, null);
            });

            eerste.verwerkTerugmelding(StatusWaarde.PERMANENT_FAILURE, null);
        }));

        assertTrue(bevatOptimisticLockException(fout),
                "Verwachtte een OptimisticLockException in de oorzaakketen, maar kreeg: " + fout);

        // De kern van het probleem: de statusregel van de gecommitte transactie mag niet stilzwijgend
        // verdwenen zijn.
        QuarkusTransaction.requiringNew().run(() -> {
            List<NotificatieStatus> geschiedenis = notificatieRepository.findById(id).getStatusGeschiedenis();

            assertEquals(2, geschiedenis.size());
            assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(1).status());
        });
    }

    // De exceptie komt naar boven verpakt door zowel JTA (RollbackException) als
    // QuarkusTransaction; welke laag precies bovenaan staat is een implementatiedetail waar deze
    // test niet op vast hoort te zitten — het gaat erom dát de versiecontrole heeft toegeslagen.
    private static boolean bevatOptimisticLockException(Throwable fout) {
        for (Throwable oorzaak = fout; oorzaak != null; oorzaak = oorzaak.getCause()) {
            if (oorzaak instanceof OptimisticLockException || oorzaak instanceof StaleStateException) {
                return true;
            }
        }

        return false;
    }
}
