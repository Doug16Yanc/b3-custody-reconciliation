package tech.b3custodyreconciliation.domain.model.reconciliation

enum class BreakType {
    /** Both sides have the position but disagree on the quantity. */
    QUANTITY_MISMATCH,

    /** The depository reports a position that our ledger does not have. */
    MISSING_INTERNALLY,

    /** Our ledger has a position that the depository does not report. */
    MISSING_AT_DEPOSITORY,
}
