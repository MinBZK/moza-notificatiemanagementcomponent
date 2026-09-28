package nl.rijksoverheid.moz.nmc.service;

import nl.rijksoverheid.moz.nmc.domain.Taak;

/**
 * De lease op een taak is verlopen en een andere worker heeft hem opnieuw geclaimd: het claim-epoch
 * op de rij is hoger dan dat van de aanroeper. Wat de aanroeper voor deze taak wilde committen, mag
 * niet doorgaan.
 */
public class TaakVerlorenException extends RuntimeException {

    public TaakVerlorenException(Taak taak) {
        super("Taak " + taak.getSoort() + "/" + taak.getId() + " is met claim-epoch " + taak.getClaimEpoch()
                + " niet meer van deze worker");
    }
}
