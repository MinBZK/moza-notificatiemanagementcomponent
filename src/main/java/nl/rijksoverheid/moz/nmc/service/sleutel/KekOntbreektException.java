package nl.rijksoverheid.moz.nmc.service.sleutel;

/**
 * De KEK-versie waaronder de sleutel van een notificatie gewrapt is, is op deze pod niet
 * geconfigureerd. Een configuratiefout, geen eigenschap van de notificatie.
 */
public class KekOntbreektException extends OntsleutelenMisluktException {

    public KekOntbreektException(String message) {
        super(message);
    }
}
