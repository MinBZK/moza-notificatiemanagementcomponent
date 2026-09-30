package nl.rijksoverheid.moz.nmc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Optional;
import java.util.UUID;

/**
 * Het register uit de onboarding. Nu één rij uit de migratie; de tokenvalidatie per dienstverlener
 * koppelt later het OIN uit het token aan deze rij.
 */
@Entity
public class Dienstverlener {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String oin;

    @Column(nullable = false, length = 200)
    private String naam;

    // Leeg is onbeperkt.
    @Column(name = "quotum_per_dag")
    private Integer quotumPerDag;

    // Leeg is geen webhook.
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    // Leeg is de default uit de configuratie.
    @Column(name = "webhook_max_mislukkingen")
    private Integer webhookMaxMislukkingen;

    protected Dienstverlener() {
        // Voor JPA
    }

    public UUID getId() {
        return id;
    }

    public String getOin() {
        return oin;
    }

    public String getNaam() {
        return naam;
    }

    /** Het maximum aantal aannames per dag, als dat begrensd is. */
    public Optional<Integer> getQuotumPerDag() {
        return Optional.ofNullable(quotumPerDag);
    }

    /** De webhook uit de onboarding, als de dienstverlener er een heeft; nog niet gevalideerd. */
    public Optional<String> getWebhookUrl() {
        return Optional.ofNullable(webhookUrl);
    }

    /** Het aantal mislukte leveringen waarna de webhook pauzeert, als daar een afspraak over is. */
    public Optional<Integer> getWebhookMaxMislukkingen() {
        return Optional.ofNullable(webhookMaxMislukkingen);
    }
}
