package tech.b3custodyreconciliation.domain.model.saga

import tech.b3custodyreconciliation.domain.exception.InvalidValueException

/**
 * Where a payout saga currently stands.
 *
 * ```
 * AwaitingStep(s1) -> AwaitingStep(s2) -> ... -> Completed
 *        |  step fails
 *        v
 *   Compensating(pending) -> Compensated
 *        |  a compensation fails
 *        v
 *   ManualInterventionRequired
 * ```
 */
sealed interface SagaState {

    /** Stable label for persistence and APIs. */
    val code: String

    val isTerminal: Boolean get() = false

    /** The orchestrator must execute [next] and report back. */
    data class AwaitingStep(val next: SagaStep) : SagaState {
        override val code = "AWAITING_STEP"
    }

    data object Completed : SagaState {
        override val code = "COMPLETED"
        override val isTerminal = true
    }

    /** Undoing completed steps. [pending] is in the order in which they must run (most recent first). */
    data class Compensating(val reason: String, val pending: List<CompensationStep>) : SagaState {
        init {
            if (pending.isEmpty()) throw InvalidValueException("Compensating state needs at least one pending step")
        }

        override val code = "COMPENSATING"
    }

    data class Compensated(val reason: String) : SagaState {
        override val code = "COMPENSATED"
        override val isTerminal = true
    }

    /** A compensation failed: the books may be inconsistent and a human must step in. */
    data class ManualInterventionRequired(
        val reason: String,
        val failedCompensation: CompensationStep,
    ) : SagaState {
        override val code = "MANUAL_INTERVENTION_REQUIRED"
        override val isTerminal = true
    }
}
