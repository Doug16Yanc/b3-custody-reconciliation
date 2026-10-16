package tech.b3custodyreconciliation.domain.model.saga

import tech.b3custodyreconciliation.domain.exception.IllegalStateTransitionException
import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PayoutSagaTest {

    private val t = Instant.parse("2026-11-05T09:00:00Z")

    private fun started() = PayoutSaga.start(UUID.randomUUID(), UUID.randomUUID(), t)

    private fun afterCalculation() = started().stepSucceeded(SagaStep.CALCULATE_ENTITLEMENTS, t)

    private fun afterCredit() = afterCalculation().stepSucceeded(SagaStep.CREDIT_ACCOUNTS, t)

    @Test
    fun `starts by waiting for the first step`() {
        val saga = started()
        assertEquals(SagaState.AwaitingStep(SagaStep.CALCULATE_ENTITLEMENTS), saga.state)
        assertFalse(saga.isTerminal)
        assertEquals(1, saga.log.size)
    }

    @Test
    fun `happy path runs all steps in order and completes`() {
        val saga = afterCredit().stepSucceeded(SagaStep.CONFIRM_WITH_DEPOSITORY, t)
        assertEquals(SagaState.Completed, saga.state)
        assertTrue(saga.isTerminal)
        assertEquals(SagaStep.entries.toList(), saga.completedSteps)
    }

    @Test
    fun `steps cannot be reported out of order`() {
        assertFailsWith<IllegalStateTransitionException> { started().stepSucceeded(SagaStep.CREDIT_ACCOUNTS, t) }
        assertFailsWith<IllegalStateTransitionException> { started().stepFailed(SagaStep.CREDIT_ACCOUNTS, "x", t) }
    }

    @Test
    fun `failure of the first step compensates immediately because nothing needs undoing`() {
        val saga = started().stepFailed(SagaStep.CALCULATE_ENTITLEMENTS, "bad snapshot", t)
        assertEquals(SagaState.Compensated("bad snapshot"), saga.state)
    }

    @Test
    fun `failure after crediting undoes the steps in reverse order`() {
        val failed = afterCredit().stepFailed(SagaStep.CONFIRM_WITH_DEPOSITORY, "rejected by depository", t)
        assertEquals(
            SagaState.Compensating(
                "rejected by depository",
                listOf(CompensationStep.REVERSE_CREDITS, CompensationStep.DISCARD_ENTITLEMENTS),
            ),
            failed.state,
        )

        val half = failed.compensationSucceeded(CompensationStep.REVERSE_CREDITS, t)
        assertEquals(
            SagaState.Compensating("rejected by depository", listOf(CompensationStep.DISCARD_ENTITLEMENTS)),
            half.state,
        )

        val done = half.compensationSucceeded(CompensationStep.DISCARD_ENTITLEMENTS, t)
        assertEquals(SagaState.Compensated("rejected by depository"), done.state)
        assertTrue(done.isTerminal)
    }

    @Test
    fun `compensations cannot run out of order`() {
        val failed = afterCredit().stepFailed(SagaStep.CONFIRM_WITH_DEPOSITORY, "rejected", t)
        assertFailsWith<IllegalStateTransitionException> {
            failed.compensationSucceeded(CompensationStep.DISCARD_ENTITLEMENTS, t)
        }
    }

    @Test
    fun `a failed compensation requires manual intervention`() {
        val failed = afterCredit().stepFailed(SagaStep.CONFIRM_WITH_DEPOSITORY, "rejected", t)
        val stuck = failed.compensationFailed(CompensationStep.REVERSE_CREDITS, "ledger unavailable", t)
        assertEquals(
            SagaState.ManualInterventionRequired("ledger unavailable", CompensationStep.REVERSE_CREDITS),
            stuck.state,
        )
        assertTrue(stuck.isTerminal)
    }

    @Test
    fun `redelivered success messages are no-ops`() {
        val saga = afterCalculation()
        assertSame(saga, saga.stepSucceeded(SagaStep.CALCULATE_ENTITLEMENTS, t.plusSeconds(5)))

        val failed = afterCredit().stepFailed(SagaStep.CONFIRM_WITH_DEPOSITORY, "rejected", t)
        val half = failed.compensationSucceeded(CompensationStep.REVERSE_CREDITS, t)
        assertSame(half, half.compensationSucceeded(CompensationStep.REVERSE_CREDITS, t.plusSeconds(5)))

        val done = half.compensationSucceeded(CompensationStep.DISCARD_ENTITLEMENTS, t)
        assertSame(done, done.compensationSucceeded(CompensationStep.DISCARD_ENTITLEMENTS, t.plusSeconds(5)))
    }

    @Test
    fun `a completed saga cannot fail afterwards`() {
        val completed = afterCredit().stepSucceeded(SagaStep.CONFIRM_WITH_DEPOSITORY, t)
        assertFailsWith<IllegalStateTransitionException> {
            completed.stepFailed(SagaStep.CONFIRM_WITH_DEPOSITORY, "late rejection", t)
        }
    }

    @Test
    fun `compensation is rejected while the saga is still moving forward`() {
        assertFailsWith<IllegalStateTransitionException> {
            afterCalculation().compensationSucceeded(CompensationStep.DISCARD_ENTITLEMENTS, t)
        }
    }

    @Test
    fun `failure reasons must not be blank`() {
        assertFailsWith<InvalidValueException> { started().stepFailed(SagaStep.CALCULATE_ENTITLEMENTS, " ", t) }
    }

    @Test
    fun `transitions never mutate the original and extend the log`() {
        val original = started()
        val next = original.stepSucceeded(SagaStep.CALCULATE_ENTITLEMENTS, t)
        assertEquals(SagaState.AwaitingStep(SagaStep.CALCULATE_ENTITLEMENTS), original.state)
        assertTrue(original.completedSteps.isEmpty())
        assertEquals(2, next.log.size)
    }

    @Test
    fun `noted extends the log without touching the state`() {
        val saga = started()
        val noted = saga.noted("attempt 1/3 failed", t.plusSeconds(1))

        assertEquals(saga.state, noted.state)
        assertEquals(saga.completedSteps, noted.completedSteps)
        assertEquals(2, noted.log.size)
        assertEquals("attempt 1/3 failed", noted.log.last().message)
        assertFailsWith<InvalidValueException> { saga.noted(" ", t) }
    }

    @Test
    fun `compensating state needs at least one pending step`() {
        assertFailsWith<InvalidValueException> { SagaState.Compensating("x", emptyList()) }
    }
}