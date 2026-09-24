package nl.rijksoverheid.moz.nmc.service;

/**
 * De notificatie heeft geen gewrapte sleutel: die is gewist of nooit vastgelegd, dus de gegevens zijn
 * niet meer te ontsleutelen.
 */
public class SleutelGewistException extends OntsleutelenMisluktException {

    public SleutelGewistException(String message) {
        super(message);
    }
}
