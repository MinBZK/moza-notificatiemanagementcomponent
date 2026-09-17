package nl.rijksoverheid.moz.nmc.domain;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
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
 * hydratatie, @OrderColumn-volgorde (registratievolgorde, kolom volgnummer) en de projectie in
 * getStatus() ook standhouden na een echte round-trip door de database,
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
        // Expliciet, ver uiteenliggend tijdstip voor de laatste overgang i.p.v. terugvallen op
        // opeenvolgende now()-aanroepen: die kunnen in dezelfde kloktik vallen, waardoor de
        // assertie op de projectie verderop ook zou slagen als die niet meer bijgewerkt werd.
        // Afgerond op microseconden: de kolom is timestamp(6), anders faalt de vergelijking met de
        // herladen (afgeronde) waarde op de nanoseconden die de database toch niet bewaart. Het
        // tijdstip wordt meegegeven aan de publieke overload die NotificatieService gebruikt om de
        // er geen productiereden is om hem breder te openen, alleen een testbehoefte.
        OffsetDateTime laatsteTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.MICROS);

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.registreerStatus(StatusWaarde.SENDING);
            notificatie.registreerStatus(StatusWaarde.TEMPORARY_FAILURE);
            registreerStatusOp(notificatie, StatusWaarde.DELIVERED, laatsteTijdstip);
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

            assertEquals(StatusWaarde.DELIVERED, herladen.getStatus().status());
            assertEquals(laatsteTijdstip, herladen.getStatus().tijdstip());
        });
    }

    // De gebeurtenistijden lopen hier bewust tegen de registratievolgorde in: SENDING krijgt een
    // latere completed_at dan DELIVERED, terwijl DELIVERED als eerste geregistreerd wordt. Na
    // herladen moet de geschiedenis nog steeds op registratievolgorde staan (@OrderColumn op
    // volgnummer) en moet de projectie de laatste registratie volgen, hier
    // SENDING. Zou er op tijdstip geordend worden, dan zou een receipt met een scheve of oude
    // completed_at de volgorde omgooien en getAangemaakt() (dat getFirst() leest) de verkeerde rij
    // teruggeven.
    @Test
    void notificatie_metGebeurtenistijdenTegenDeRegistratievolgordeIn_herlaadtOpRegistratievolgorde() {
        OffsetDateTime deliveredTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime sendingTijdstip = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.MICROS);

        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Notificatie notificatie = new Notificatie(null);
            registreerStatusOp(notificatie, StatusWaarde.DELIVERED, deliveredTijdstip);
            registreerStatusOp(notificatie, StatusWaarde.SENDING, sendingTijdstip);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            List<NotificatieStatus> geschiedenis = herladen.getStatusGeschiedenis();
            assertEquals(3, geschiedenis.size());
            assertEquals(StatusWaarde.CREATED, geschiedenis.get(0).status());
            assertEquals(StatusWaarde.DELIVERED, geschiedenis.get(1).status());
            assertEquals(StatusWaarde.SENDING, geschiedenis.get(2).status());

            assertEquals(StatusWaarde.SENDING, herladen.getStatus().status());
            assertEquals(sendingTijdstip, herladen.getStatus().tijdstip());
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
            notificatie.registreerStatus(StatusWaarde.DELIVERED, ruw);
            notificatieRepository.persist(notificatie);

            return notificatie.getId();
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie herladen = notificatieRepository.findById(id);

            assertEquals(verwacht, herladen.getStatus().tijdstip());
            assertEquals(ZoneOffset.UTC, herladen.getStatus().tijdstip().getOffset());
            assertEquals(ZoneOffset.UTC, herladen.getStatus().geregistreerd().getOffset());
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
                notificatieRepository.getEntityManager()
                        .createNativeQuery("INSERT INTO notificatie_status "
                                + "(notificatie_id, volgnummer, status, tijdstip, geregistreerd) "
                                + "VALUES (?1, 0, 'SENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                        .setParameter(1, id)
                        .executeUpdate()),
                "volgnummer 0 is al bezet door de CREATED uit de constructor");
    }

    // Bewaakt dat de CHECK-constraint op notificatie_status.status (V2__notificatie_retentie.sql)
    // elke StatusWaarde-constante toestaat. Zonder deze test zou een nieuwe of hernoemde constante
    // compileren en alle Java-tests laten slagen, maar pas bij de eerste echte INSERT in productie
    // op een constraint-violation stuiten.
    @ParameterizedTest
    @EnumSource(StatusWaarde.class)
    void registreerStatus_elkeStatusWaarde_voldoetAanDatabaseCheckConstraint(StatusWaarde status) {
        QuarkusTransaction.requiringNew().run(() -> {
            Notificatie notificatie = new Notificatie(null);
            notificatie.registreerStatus(status);
            notificatieRepository.persist(notificatie);
            notificatieRepository.flush();
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
                tweede.registreerStatus(StatusWaarde.DELIVERED);
            });

            eerste.registreerStatus(StatusWaarde.PERMANENT_FAILURE);
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

    // registreerStatus(status, gebeurtenistijd) is publiek sinds NotificatieService hem gebruikt om
    // de completed_at van een NotifyNL-receipt vast te leggen; deze helper blijft staan om te laten
    // zien dat dit hier bewust een terug- of vooruitgedateerde registratie is.
    private static void registreerStatusOp(Notificatie notificatie, StatusWaarde status, OffsetDateTime tijdstip) {
        notificatie.registreerStatus(status, tijdstip);
    }
}
