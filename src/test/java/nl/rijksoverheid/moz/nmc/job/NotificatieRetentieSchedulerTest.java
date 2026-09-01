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

    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class,
            names = {"DELIVERED", "PERMANENT_FAILURE", "TEMPORARY_FAILURE", "TECHNICAL_FAILURE"})
    void verwijderVerlopenNotificaties_verwijdertElkeDefinitieveStatusOuderDanDeBewaartermijn(StatusWaarde status) {
        UUID verlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenId = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenId).isPresent());
        });
    }

    // Een notificatie zonder definitieve status (NotifyNL heeft er nog geen statusupdate over
    // gestuurd) wordt door deze job nooit verwijderd, ongeacht ouderdom — alleen gesignaleerd
    // (WARN-log in verwijderVerlopenNotificaties, hier niet apart geasserteerd).
    @ParameterizedTest
    @EnumSource(value = StatusWaarde.class, names = {"CREATED", "SENDING"})
    void verwijderVerlopenNotificaties_bewaartElkeNietDefinitieveStatusOngeachtOuderdom(StatusWaarde status) {
        UUID id = maakNotificatie(null, status, OffsetDateTime.now(ZoneOffset.UTC).minusDays(40));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() ->
                assertTrue(notificatieRepository.findByIdOptional(id).isPresent()));
    }

    // Dekt de realistische situatie waarin beide queries tegelijk iets te doen hebben: alleen de
    // verlopen+definitieve rij mag weg, de verlopen+niet-definitieve en de niet-verlopen+definitieve
    // rij moeten allebei blijven staan. Pint zowel de IN- als de NOT IN-voorwaarde tegen elkaar af.
    @Test
    void verwijderVerlopenNotificaties_metGemengdePopulatie_verwijdertAlleenDeVerlopenDefinitieveRij() {
        UUID verlopenDefinitiefId = maakNotificatie(null, StatusWaarde.DELIVERED,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(31));
        UUID nietVerlopenDefinitiefId = maakNotificatie(null, StatusWaarde.PERMANENT_FAILURE,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        UUID verlopenNietDefinitiefId = maakNotificatie(null, StatusWaarde.SENDING,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(40));

        scheduler.verwijderVerlopenNotificaties();

        QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(notificatieRepository.findByIdOptional(verlopenDefinitiefId).isEmpty());
            assertTrue(notificatieRepository.findByIdOptional(nietVerlopenDefinitiefId).isPresent());
            assertTrue(notificatieRepository.findByIdOptional(verlopenNietDefinitiefId).isPresent());
        });
    }

    // Bewust vastgelegd gedrag, geen toevalstreffer: een notificatie mét callbackUrl die wél een
    // definitieve NotifyNL-status heeft bereikt, maar waarvan de callback naar de Dienstverlener
    // nooit is gelukt, wordt na de bewaartermijn alsnog verwijderd. Verwijderen is volledig
    // losgekoppeld van het afleveren van de callback (zie NotificatieService.verwerkAfleverstatus).
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
    // verdwijnt — de ON DELETE CASCADE-foreignkey wordt hier niet aangesproken (zie de aparte
    // ...ViaForeignKeyCascade-test hieronder voor een test die dát wél doet).
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
