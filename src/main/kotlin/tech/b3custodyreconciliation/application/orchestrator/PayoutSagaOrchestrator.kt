package tech.b3custodyreconciliation.application.orchestrator

import tech.b3custodyreconciliation.application.port.out.DepositoryPort
import tech.b3custodyreconciliation.application.port.out.EntitlementPort
import tech.b3custodyreconciliation.application.port.out.LedgerPort
import tech.b3custodyreconciliation.application.port.out.RejectedException
import tech.b3custodyreconciliation.application.port.out.SagaRepository
import tech.b3custodyreconciliation.application.port.out.TransientFailureException
import tech.b3custodyreconciliation.domain.model.saga.CompensationStep
import tech.b3custodyreconciliation.domain.model.saga.SagaState
import tech.b3custodyreconciliation.domain.model.saga.SagaStep
import tech.b3custodyreconciliation.domain.model.saga.PayoutSaga
import java.time.Clock
import java.util.UUID

class SagaNotFoundException(sagaId: UUID) : RuntimeException("No saga with id $sagaId")

/**
 * Drives a [PayoutSaga] to a terminal state by calling the outbound ports.
 *
 * Behaviour worth knowing:
 *  - The saga is saved after every transition, so [resume] can continue after a crash.
 *  - Every attempt is written to the saga's log, and saved BEFORE the action runs (write-ahead).
 *    If the process dies mid-action, the saved log shows an attempt that started and never finished.
 *  - A [RejectedException] is a definitive "no": the saga fails the step and compensates.
 *  - A [TransientFailureException] is retried up to [maxAttempts] times. If it keeps failing the
 *    orchestrator pauses without changing the saga's state; call [resume] later.
 *    A paused saga is not a failed saga.
 *  - Anything else (a bug, a crash) propagates. The saga stays at its last saved state.
 *  - [start] is idempotent per event: a second start for the same event continues the existing
 *    saga instead of creating another one, which prevents paying the same event twice.
 *    A saga that already compensated is not retried automatically.
 *
 * Retries here are immediate. A real adapter would add backoff and jitter.
 */
class PayoutSagaOrchestrator(
    private val sagas: SagaRepository,
    private val entitlements: EntitlementPort,
    private val ledger: LedgerPort,
    private val depository: DepositoryPort,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = 3,
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
) {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun start(eventId: UUID): PayoutSaga {
        val existing = sagas.findByEventId(eventId)
        if (existing != null) return continueFrom(existing as PayoutSaga)

        val saga = PayoutSaga.start(idGenerator(), eventId, clock.instant())
        sagas.save(saga)
        return drive(saga)
    }

    fun resume(sagaId: UUID): PayoutSaga {
        val saga = sagas.find(sagaId) ?: throw SagaNotFoundException(sagaId)
        return continueFrom(saga)
    }

    private fun continueFrom(saga: PayoutSaga): PayoutSaga {
        if (saga.isTerminal) return saga
        val resumed = saga.noted("resumed from saved state ${saga.state}", clock.instant())
        sagas.save(resumed)
        return drive(resumed)
    }

    private fun drive(initial: PayoutSaga): PayoutSaga {
        var saga = initial
        while (!saga.isTerminal) {
            val progress = advance(saga)
            saga = progress.saga
            sagas.save(saga)
            if (progress.paused) break
        }
        return saga
    }

    private fun advance(saga: PayoutSaga): Progress =
        when (val state = saga.state) {
            is SagaState.AwaitingStep -> forward(saga, state.next)
            is SagaState.Compensating -> backward(saga, state.pending.first())
            // Unreachable: drive() only calls this for non-terminal states. Pausing is the safe default.
            else -> Progress(saga, paused = true)
        }

    private fun forward(saga: PayoutSaga, step: SagaStep): Progress =
        execute(
            saga = saga,
            label = step.name,
            action = { perform(saga, step) },
            onSuccess = { current -> current.stepSucceeded(step, clock.instant()) },
            onRejected = { current, reason -> current.stepFailed(step, reason, clock.instant()) },
        )

    private fun backward(saga: PayoutSaga, compensation: CompensationStep): Progress =
        execute(
            saga = saga,
            label = "compensation ${compensation.name}",
            action = { compensate(saga, compensation) },
            onSuccess = { current -> current.compensationSucceeded(compensation, clock.instant()) },
            onRejected = { current, reason -> current.compensationFailed(compensation, reason, clock.instant()) },
        )

    /**
     * Runs [action] with retries for transient failures, logging every attempt on the saga.
     * Each attempt is saved before it runs, so an interrupted attempt stays visible in the log.
     */
    private fun execute(
        saga: PayoutSaga,
        label: String,
        action: () -> Unit,
        onSuccess: (PayoutSaga) -> PayoutSaga,
        onRejected: (PayoutSaga, String) -> PayoutSaga,
    ): Progress {
        var current = saga
        for (attempt in 1..maxAttempts) {
            current = current.noted("starting $label (attempt $attempt/$maxAttempts)", clock.instant())
            sagas.save(current)
            try {
                action()
                return Progress(onSuccess(current), paused = false)
            } catch (e: RejectedException) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "rejected without a reason"
                return Progress(onRejected(current, reason), paused = false)
            } catch (e: TransientFailureException) {
                val detail = e.message ?: "no details"
                current = current.noted(
                    "$label attempt $attempt/$maxAttempts failed (transient): $detail",
                    clock.instant(),
                )
            }
        }
        current = current.noted("$label paused after $maxAttempts failed attempts; resume later", clock.instant())
        return Progress(current, paused = true)
    }

    private fun perform(saga: PayoutSaga, step: SagaStep) =
        when (step) {
            SagaStep.CALCULATE_ENTITLEMENTS -> entitlements.calculate(saga.id, saga.eventId)
            SagaStep.CREDIT_ACCOUNTS -> ledger.credit(saga.id)
            SagaStep.CONFIRM_WITH_DEPOSITORY -> depository.confirm(saga.id, saga.eventId)
        }

    private fun compensate(saga: PayoutSaga, compensation: CompensationStep) =
        when (compensation) {
            CompensationStep.DISCARD_ENTITLEMENTS -> entitlements.discard(saga.id)
            CompensationStep.REVERSE_CREDITS -> ledger.reverse(saga.id)
        }

    private data class Progress(val saga: PayoutSaga, val paused: Boolean)
}