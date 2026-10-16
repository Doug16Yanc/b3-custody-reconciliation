package tech.b3custodyreconciliation.domain.model.reconciliation

/**
 * Lifecycle of a reconciliation break.
 *
 * ```
 * OPEN -> UNDER_REVIEW -> ADJUSTED                 (we fix our ledger)
 *                      -> DISPUTED -> RESOLVED     (the depository corrects its side)
 *                                  -> ADJUSTED     (the dispute is rejected, we fix our ledger)
 * ```
 * [Adjusted] and [Resolved] are terminal.
 */
sealed interface BreakStatus {

    /** Stable label for persistence and APIs. */
    val code: String

    val isTerminal: Boolean get() = false

    data object Open : BreakStatus {
        override val code = "OPEN"
    }

    data class UnderReview(val analyst: String) : BreakStatus {
        override val code = "UNDER_REVIEW"
    }

    data class Adjusted(val reason: String) : BreakStatus {
        override val code = "ADJUSTED"
        override val isTerminal = true
    }

    data class Disputed(val reference: String) : BreakStatus {
        override val code = "DISPUTED"
    }

    data object Resolved : BreakStatus {
        override val code = "RESOLVED"
        override val isTerminal = true
    }
}
