package nl.rijksoverheid.moz.nmc.domain;

import java.util.Optional;

/**
 * De soorten bijwerkingen die als taak in de takentabel staan. De tabel is per soort
 * gepartitioneerd; een nieuwe soort vraagt dus ook een migratie.
 */
public enum TaakSoort {
    VERZENDEN(BudgetDoel.VERZENDEN),
    RECEIPT_VERWERKEN(null),
    RECONCILIEREN(BudgetDoel.NAVRAAG),
    BEZORGING_VASTSTELLEN(null),
    TERUGKOPPELEN(null),
    ONGELDIG_MELDEN(null),
    WISSEN(null),
    CONTROLE(null),
    ONDERHOUD(null);

    private final BudgetDoel budgetDoel;

    TaakSoort(BudgetDoel budgetDoel) {
        this.budgetDoel = budgetDoel;
    }

    /** Het doel waarvoor een claim van deze soort tokens uit het verzendbudget neemt, als dat zo is. */
    public Optional<BudgetDoel> budgetDoel() {
        return Optional.ofNullable(budgetDoel);
    }
}
