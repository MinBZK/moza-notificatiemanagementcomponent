package nl.rijksoverheid.moz.nmc.job;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nl.rijksoverheid.moz.nmc.domain.Notificatie;
import nl.rijksoverheid.moz.nmc.domain.StatusWaarde;
import nl.rijksoverheid.moz.nmc.repository.NotificatieRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class NotificatieRetentieSchedulerTest {

    @Inject
    NotificatieRepository notificatieRepository;

    @Inject
    NotificatieRetentieScheduler scheduler;

    @BeforeEach
    void setUp() {
        QuarkusTransaction.requiringNew().run(notificatieRepository::deleteAll);
    }

    // Geen onderscheid naar status: elke status verloopt op dezelfde bewaartermijn.
    @ParameterizedTest
    @EnumSource(StatusWaarde.class)
    void verwijderVerlopenNotificaties_verwijdertElkeStatusOuderDanDeBewaartermijn(StatusWaarde status) {
        UUID verlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // Dekt de realistische situatie waarin verschillende statussen tegelijk in de tabel staan: enkel
    // ouderdom bepaalt of een rij weg moet, de status zelf doet er niet toe.
    @Test
    void verwijderVerlopenNotificaties_metGemengdePopulatie_verwijdertAlleenDeVerlopenRijen() {
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // De verwijdering gebeurt in batches (zie NotificatieRetentieScheduler#BATCH_GROOTTE = 1000) om
    // de transactie begrensd te houden ongeacht de achterstand. Dit zet er express meer dan één
    // batch aan verlopen rijen neer om aan te tonen dat de lus doorgaat tot alles weg is, niet dat
    // hij na de eerste batch stopt. Rijen worden met ruwe SQL geplant (i.p.v. maakNotificatie 1000+
    // keer aan te roepen) puur om de test snel te houden — dit test geen entiteitgedrag, alleen de
    // batchlus.
    @Test
    void verwijderVerlopenNotificaties_meerDanEenBatchAanRijen_verwijdertUiteindelijkAlles() {
        int aantalRijen = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.getEntityManager()
                .createNativeQuery("INSERT INTO notificatie (id, status, aangemaakt, laatste_status_update) "
                        + "SELECT RANDOM_UUID(), 'DELIVERED', ?1, ?1 FROM SYSTEM_RANGE(1, ?2)")
                .setParameter(1, verlopenTijdstip)
                .setParameter(2, aantalRijen)
                .executeUpdate());

        scheduler.verwijderVerlopenNotificaties();

        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(0L, overgebleven);
    }

    // Vult aan op de vorige test: die bewijst dat de lus doorgaat tot alles weg is, maar zou ook
    // slagen voor een enkele onbegrensde DELETE (geen batching) — het eindresultaat is hetzelfde.
    // Deze test roept verwijderBatch (privé; via reflectie, zie registreerStatusOp-achtige aanpak
    // elders in deze suite voor waarom geen productiecode-zichtbaarheid hiervoor wordt opgerekt)
    // rechtstreeks aan en bewijst dat één aanroep écht begrensd is tot BATCH_GROOTTE.
    @Test
    void verwijderBatch_metMeerKandidatenDanBatchGrootte_verwijdertPreciesBatchGrootte() {
        int aantalRijen = 1005;
        OffsetDateTime verlopenTijdstip = OffsetDateTime.now(ZoneOffset.UTC).minusDays(31);

        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.getEntityManager()
                .createNativeQuery("INSERT INTO notificatie (id, status, aangemaakt, laatste_status_update) "
                        + "SELECT RANDOM_UUID(), 'DELIVERED', ?1, ?1 FROM SYSTEM_RANGE(1, ?2)")
                .setParameter(1, verlopenTijdstip)
                .setParameter(2, aantalRijen)
                .executeUpdate());

        // grens is "nu", niet exact verlopenTijdstip: een gelijke grens loopt tegen
        // afrondingsverschil in de timestamp(6)-kolom aan (opgeslagen waarde vs. in-memory waarde
        // met nanoseconden), terwijl er hier alleen "ruim verlopen" getoetst hoeft te worden.
        NotificatieRetentieScheduler.BatchResultaat eersteBatch = QuarkusTransaction.requiringNew()
                .call(() -> verwijderBatchOp(OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(1000, eersteBatch.totaal());
        long overgebleven = QuarkusTransaction.requiringNew().call(() -> notificatieRepository.count());
        assertEquals(5L, overgebleven);
    }

    // Pint de definitief/niet-definitief-telling die de WARN-log voedt: een niet-definitieve rij
    // (SENDING) moet meetellen, een definitieve (DELIVERED) niet — ook al worden beide verwijderd.
    @Test
    void verwijderBatch_metGemengdeStatussen_teltAlleenNietDefinitieveRijenApart() {
        maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        maakNotificatie(null, StatusWaarde.SENDING, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        maakNotificatie(null, StatusWaarde.TEMPORARY_FAILURE, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        NotificatieRetentieScheduler.BatchResultaat resultaat = QuarkusTransaction.requiringNew()
                .call(() -> verwijderBatchOp(OffsetDateTime.now(ZoneOffset.UTC)));

        assertEquals(3, resultaat.totaal());
        assertEquals(1, resultaat.nietDefinitiefAantal());
    }

    // Bewust vastgelegd gedrag, geen toevalstreffer: een notificatie mét callbackUrl waarvan de
    // callback naar de Dienstverlener nooit is gelukt, wordt na de bewaartermijn alsnog verwijderd.
    // Verwijderen is volledig losgekoppeld van het afleveren van de callback (zie
    // NotificatieService.verwerkAfleverstatus).
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokNotificatiesWaarvanDeCallbackNooitIsGelukt() {
        UUID verlopenIdMetCallbackUrl = maakNotificatie("https://omc.example.nl/callback-die-nooit-lukt",
                StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(verlopenIdMetCallbackUrl).isEmpty()));
    }

    // Deze test dekt wat Hibernate zelf al doet: bij een JPQL bulk-delete ruimt Hibernate de
    // @ElementCollection-rijen (notificatie_status) zelf op vóórdat het notificatie-record
    // verdwijnt — de ON DELETE CASCADE-foreignkey wordt hier niet aangesproken. Let op: dit draait
    // op H2 (de teststack), dat een andere mutation-strategy gebruikt dan Postgres (productie); de
    // FK is en blijft het vangnet dat op beide dialecten hoort te werken (zie de aparte
    // ...ViaForeignKeyCascade-test hieronder, die de FK zelf dwingt, dialect-onafhankelijk).
    @Test
    void verwijderVerlopenNotificaties_verwijdertOokDeStatusGeschiedenis() {
        UUID verlopenId = maakNotificatie(null, StatusWaarde.DELIVERED, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));

        assertTrue(aantalNotificatieStatussenVoor(verlopenId) > 0);

        scheduler.verwijderVerlopenNotificaties();

        assertEquals(0L, aantalNotificatieStatussenVoor(verlopenId));
    }

    // In tegenstelling tot de test hierboven gaat deze buiten Hibernate om: een rechtstreekse SQL
    // DELETE op notificatie triggert geen enkele Hibernate-cascade-logica, dus dit is de enige test
    // die de DB-foreignkey (ON DELETE CASCADE in V2__notificatie_retentie.sql) daadwerkelijk dwingt
    // om de statusgeschiedenis op te ruimen.
    @Test
    void notificatieVerwijderenViaRuweSql_verwijdertStatusGeschiedenisViaForeignKeyCascade() {
        UUID id = maakNotificatie(null, StatusWaarde.CREATED, OffsetDateTime.now(ZoneOffset.UTC));
        assertTrue(aantalNotificatieStatussenVoor(id) > 0);

        QuarkusTransaction.requiringNew().run(() -> notificatieRepository.getEntityManager()
                .createNativeQuery("DELETE FROM notificatie WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate());

        assertEquals(0L, aantalNotificatieStatussenVoor(id));
    }

    private UUID maakNotificatie(String callbackUrl, StatusWaarde status, OffsetDateTime laatsteStatusUpdate) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // De constructor registreert CREATED al zelf — nogmaals registreren zou een dubbel
            // geschiedenisrecord opleveren.
            Notificatie notificatie = new Notificatie(callbackUrl);
            if (status != StatusWaarde.CREATED) {
                notificatie.registreerStatus(status);
            }
            notificatieRepository.persist(notificatie);
            notificatieRepository.flush();

            notificatieRepository.getEntityManager()
                    .createQuery("UPDATE Notificatie n SET n.laatsteStatusUpdate = :tijdstip WHERE n.id = :id")
                    .setParameter("tijdstip", laatsteStatusUpdate)
                    .setParameter("id", notificatie.getId())
                    .executeUpdate();

            return notificatie.getId();
        });
    }

    // Reflecteert op een rechtstreeks geconstrueerde instantie, niet op het @Inject-veld: dat laatste
    // is een CDI-clientproxy waarvan de eigen velden (notificatieRepository) leeg zijn — een privé
    // methode via reflectie op de proxy aanroepen omzeilt de CDI-delegatie en geeft een NPE. De
    // meegegeven Duration doet er niet toe: verwijderBatch gebruikt alleen de grens-parameter.
    private NotificatieRetentieScheduler.BatchResultaat verwijderBatchOp(OffsetDateTime grens) {
        try {
            NotificatieRetentieScheduler kaleScheduler =
                    new NotificatieRetentieScheduler(notificatieRepository, Duration.ofDays(30));
            Method methode = NotificatieRetentieScheduler.class.getDeclaredMethod("verwijderBatch", OffsetDateTime.class);
            methode.setAccessible(true);
            return (NotificatieRetentieScheduler.BatchResultaat) methode.invoke(kaleScheduler, grens);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // NotificatieStatus is een @Embeddable (@ElementCollection), dus niet zelfstandig bevraagbaar
    // via JPQL — de collection-tabel wordt hier rechtstreeks met SQL geteld, ook zodat deze telling
    // blijft werken nadat de bijbehorende Notificatie al is verwijderd.
    private long aantalNotificatieStatussenVoor(UUID notificatieId) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) notificatieRepository.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM notificatie_status WHERE notificatie_id = ?1")
                .setParameter(1, notificatieId)
                .getSingleResult()).longValue());
    }
}
