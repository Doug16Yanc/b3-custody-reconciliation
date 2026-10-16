package tech.b3custodyreconciliation.domain.model.saga

/** Undo actions. Each one is a new, opposite ledger entry, never a deletion. */
enum class CompensationStep {
    DISCARD_ENTITLEMENTS,
    REVERSE_CREDITS,
}

/**
 * Forward steps of the payout saga, in execution order.
 * [compensation] is what must be run to undo the step once it has succeeded; null if nothing needs undoing.
 */
enum class SagaStep(val compensation: CompensationStep?) {
    CALCULATE_ENTITLEMENTS(CompensationStep.DISCARD_ENTITLEMENTS),
    CREDIT_ACCOUNTS(CompensationStep.REVERSE_CREDITS),
    CONFIRM_WITH_DEPOSITORY(null),
    ;

    fun next(): SagaStep? = entries.getOrNull(ordinal + 1)
}
