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
}
