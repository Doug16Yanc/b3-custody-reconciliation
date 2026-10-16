package tech.b3custodyreconciliation.domain.model.saga

import tech.b3custodyreconciliation.domain.exception.IllegalStateTransitionException
import tech.b3custodyreconciliation.domain.exception.InvalidValueException

import java.time.Instant
import java.util.UUID

/** One line of the saga's audit log. */
data class SagaLogEntry(val at: Instant, val message: String)

/**
 * Orchestration state for paying out one corporate event.
 *
 * Steps run in order: calculate entitlements, credit accounts, confirm with the depository.
 * If a step fails, the steps that already succeeded are undone in reverse order.
 *
 * Immutable: every transition returns a new instance. Reporting a step or compensation that was
 * already recorded as successful is a no-op, so a redelivered message cannot corrupt the saga.
 */
class PayoutSaga private constructor(
    val id: UUID,
    val eventId: UUID,
    val state: SagaState,
    val completedSteps: List<SagaStep>,
    val log: List<SagaLogEntry>,
) {

    val isTerminal: Boolean get() = state.isTerminal

    fun stepSucceeded(step: SagaStep, at: Instant): PayoutSaga {
        if (step in completedSteps) return this
        val current = state as? SagaState.AwaitingStep ?: throw illegal("$step succeeded")
        if (current.next != step) throw illegal("$step succeeded out of order, expected ${current.next}")

        val following = step.next()
        val newState = if (following == null) SagaState.Completed else SagaState.AwaitingStep(following)
        return copyWith(newState, completedSteps + step, at, "$step succeeded")
    }

    fun stepFailed(step: SagaStep, reason: String, at: Instant): PayoutSaga {
        requireText(reason)
        val current = state as? SagaState.AwaitingStep ?: throw illegal("$step failed")
        if (current.next != step) throw illegal("$step failed out of order, expected ${current.next}")

        val pending = requiredCompensations().reversed()
        val newState =
            if (pending.isEmpty()) SagaState.Compensated(reason) else SagaState.Compensating(reason, pending)
        return copyWith(newState, completedSteps, at, "$step failed: $reason")
    }

    fun compensationSucceeded(compensation: CompensationStep, at: Instant): PayoutSaga {
        val current = state
        if (current !is SagaState.Compensating) {
            if (current is SagaState.Compensated && compensation in requiredCompensations()) return this
            throw illegal("compensation $compensation succeeded")
        }
        if (compensation !in current.pending) {
            if (compensation in requiredCompensations()) return this
            throw illegal("compensation $compensation is not pending")
        }
        if (current.pending.first() != compensation) {
            throw illegal("compensation $compensation out of order, expected ${current.pending.first()}")
        }

        val remaining = current.pending.drop(1)
        val newState =
            if (remaining.isEmpty()) SagaState.Compensated(current.reason)
            else SagaState.Compensating(current.reason, remaining)
        return copyWith(newState, completedSteps, at, "compensation $compensation succeeded")
    }

    fun compensationFailed(compensation: CompensationStep, reason: String, at: Instant): PayoutSaga {
        requireText(reason)
        val current = state as? SagaState.Compensating ?: throw illegal("compensation $compensation failed")
        if (current.pending.first() != compensation) {
            throw illegal("compensation $compensation failed out of order, expected ${current.pending.first()}")
        }
        val newState = SagaState.ManualInterventionRequired(reason, compensation)
        return copyWith(newState, completedSteps, at, "compensation $compensation failed: $reason")
    }

    /**
     * Records an observation (an attempt, a retry, a resume) in the audit log
     * without touching the state or the completed steps.
     */
    fun noted(message: String, at: Instant): PayoutSaga {
        requireText(message, "message")
        return copyWith(state, completedSteps, at, message)
    }

    /** Compensations for every step completed so far, in the order the steps ran. */
    private fun requiredCompensations(): List<CompensationStep> = completedSteps.mapNotNull { it.compensation }

    private fun copyWith(newState: SagaState, steps: List<SagaStep>, at: Instant, message: String) =
        PayoutSaga(id, eventId, newState, steps, log + SagaLogEntry(at, message))

    private fun illegal(attempted: String) = IllegalStateTransitionException("PayoutSaga", state.code, attempted)

    private fun requireText(value: String, field: String = "reason") {
        if (value.isBlank()) throw InvalidValueException("$field must not be blank")
    }

    override fun equals(other: Any?): Boolean = other is PayoutSaga && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "PayoutSaga($id, event=$eventId, ${state.code})"

    companion object {
        fun start(id: UUID, eventId: UUID, at: Instant): PayoutSaga = PayoutSaga(
            id = id,
            eventId = eventId,
            state = SagaState.AwaitingStep(SagaStep.entries.first()),
            completedSteps = emptyList(),
            log = listOf(SagaLogEntry(at, "saga started")),
        )
    }
}