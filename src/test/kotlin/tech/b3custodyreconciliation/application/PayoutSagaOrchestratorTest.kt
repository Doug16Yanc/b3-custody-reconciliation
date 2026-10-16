package tech.custodyreconciliation.application

import tech.b3custodyreconciliation.application.orchestrator.PayoutSagaOrchestrator
import tech.b3custodyreconciliation.application.orchestrator.SagaNotFoundException

..application.port.out.DepositoryPort
..application.port.out.EntitlementPort
..application.port.out.LedgerPort
..application.port.out.RejectedException
..application.port.out.TransientFailureException
..domain.model.event.Dividend
..domain.model.event.Rate
..domain.model.saga.CompensationStep
..domain.model.saga.SagaState
..domain.model.saga.SagaStep
..domain.model.shared.InvestorAccount
..domain.model.shared.Isin
..domain.model.shared.Money
..domain.model.shared.Quantity
..infrastructure.inmemory.InMemoryDepository
..infrastructure.inmemory.InMemoryEntitlementStore
..infrastructure.inmemory.InMemoryLedger
..infrastructure.inmemory.InMemorySagaRepository
..infrastructure.inmemory.LedgerEntryKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class SimulatedCrash : RuntimeException("crash")

private class CrashOnceLedger(private val delegate: LedgerPort) : LedgerPort by delegate {
    private var armed = true

    override fun credit(sagaId: UUID) {
        delegate.credit(sagaId)
        if (armed) {
            armed = false
            throw SimulatedCrash()
        }
    }
}

class PayoutSagaOrchestratorTest {

    private val sagaId = UUID(0L, 1L)
    private val event = Dividend(
        UUID(0L, 2L), Isin("BRPETRACNPR6"),
        LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 5), Rate.of("3.3333333333"),
    )
    private val a = InvestorAccount("ACC-A")
    private val b = InvestorAccount("ACC-B")
    private val c = InvestorAccount("ACC-C")
    private val positions = mapOf(a to Quantity.of(1), b to Quantity.of(1), c to Quantity.of(1))
    private val clock = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC)

    private val store = InMemoryEntitlementStore(event, positions, Money(1_000))
    private val ledger = InMemoryLedger(store)
    private val sagas = InMemorySagaRepository()

    private fun orchestrator(
        depository: DepositoryPort = InMemoryDepository(),
        ledgerPort: LedgerPort = ledger,
        entitlementPort: EntitlementPort = store,
    ) = PayoutSagaOrchestrator(
        sagas, entitlementPort, ledgerPort, depository,
        clock = clock, maxAttempts = 3, idGenerator = { sagaId },
    )

    private fun balances() = listOf(a, b, c).map { ledger.balanceOf(it) }

    @Test
    fun `happy path completes and splits the amount exactly`() {
        val saga = orchestrator().start(event.id)

        assertEquals(SagaState.Completed, saga.state)
        assertEquals(listOf(334L, 333L, 333L), balances())
        assertEquals(1_000L, balances().sum())
        assertSame(saga, sagas.find(sagaId))
    }

    @Test
    fun `a depository rejection reverses the credits and discards the entitlements`() {
        val saga = orchestrator(depository = InMemoryDepository { throw RejectedException("ISIN suspended") })
            .start(event.id)

        assertEquals(SagaState.Compensated("ISIN suspended"), saga.state)
        assertEquals(listOf(0L, 0L, 0L), balances())
        assertEquals(
            List(3) { LedgerEntryKind.CREDIT } + List(3) { LedgerEntryKind.REVERSAL },
            ledger.entries().map { it.kind },
        )
        assertNull(store.find(sagaId))
    }

    @Test
    fun `failing the first step leaves nothing to undo`() {
        val failing = object : EntitlementPort {
            override fun calculate(sagaId: UUID, eventId: UUID) {
                throw RejectedException("no position snapshot")
            }

            override fun discard(sagaId: UUID) {}
        }

        val saga = orchestrator(entitlementPort = failing).start(event.id)

        assertEquals(SagaState.Compensated("no position snapshot"), saga.state)
        assertTrue(ledger.entries().isEmpty())
    }

    @Test
    fun `transient failures are retried within the attempt limit`() {
        val depository = InMemoryDepository { attempt ->
            if (attempt < 3) throw TransientFailureException("timeout")
        }

        val saga = orchestrator(depository = depository).start(event.id)

        assertEquals(SagaState.Completed, saga.state)
        assertEquals(3, depository.callCount)
    }

    @Test
    fun `exhausted retries pause the saga without failing it, and resume finishes it`() {
        val depository = InMemoryDepository { attempt ->
            if (attempt <= 3) throw TransientFailureException("timeout")
        }

        val paused = orchestrator(depository = depository).start(event.id)

        assertFalse(paused.isTerminal)
        assertEquals(SagaState.AwaitingStep(SagaStep.CONFIRM_WITH_DEPOSITORY), paused.state)
        assertEquals(
            "CONFIRM_WITH_DEPOSITORY paused after 3 failed attempts; resume later",
            paused.log.last().message,
        )
        assertEquals(3, depository.callCount)
        assertEquals(listOf(334L, 333L, 333L), balances())

        val finished = orchestrator(depository = depository).resume(sagaId)

        assertEquals(SagaState.Completed, finished.state)
        assertEquals(4, depository.callCount)
    }

    @Test
    fun `a crash after crediting is safe because crediting is idempotent`() {
        assertFailsWith<SimulatedCrash> {
            orchestrator(ledgerPort = CrashOnceLedger(ledger)).start(event.id)
        }
        assertEquals(SagaState.AwaitingStep(SagaStep.CREDIT_ACCOUNTS), sagas.find(sagaId)?.state)
        assertEquals(3, ledger.entries().size)

        val resumed = orchestrator().resume(sagaId)

        assertEquals(SagaState.Completed, resumed.state)
        assertEquals(3, ledger.entries().size)
        assertEquals(listOf(334L, 333L, 333L), balances())
    }

    @Test
    fun `a failing compensation ends in manual intervention`() {
        val stubbornLedger = object : LedgerPort by ledger {
            override fun reverse(sagaId: UUID) {
                throw RejectedException("ledger locked")
            }
        }

        val saga = orchestrator(
            depository = InMemoryDepository { throw RejectedException("ISIN suspended") },
            ledgerPort = stubbornLedger,
        ).start(event.id)

        assertEquals(
            SagaState.ManualInterventionRequired("ledger locked", CompensationStep.REVERSE_CREDITS),
            saga.state,
        )
        assertEquals(listOf(334L, 333L, 333L), balances())
    }

    @Test
    fun `starting the same event twice never pays it twice`() {
        val depository = InMemoryDepository()
        val first = orchestrator(depository = depository).start(event.id)
        val second = orchestrator(depository = depository).start(event.id)

        assertEquals(SagaState.Completed, first.state)
        assertSame(first, second)
        assertEquals(3, ledger.entries().size)
        assertEquals(1, depository.callCount)
    }

    @Test
    fun `resuming a finished saga does nothing`() {
        val depository = InMemoryDepository()
        val done = orchestrator(depository = depository).start(event.id)

        val again = orchestrator(depository = depository).resume(sagaId)

        assertSame(done, again)
        assertEquals(1, depository.callCount)
    }

    @Test
    fun `the log records every attempt, including the retried ones`() {
        val depository = InMemoryDepository { attempt ->
            if (attempt < 3) throw TransientFailureException("timeout")
        }

        val saga = orchestrator(depository = depository).start(event.id)

        assertEquals(
            listOf(
                "saga started",
                "starting CALCULATE_ENTITLEMENTS (attempt 1/3)",
                "CALCULATE_ENTITLEMENTS succeeded",
                "starting CREDIT_ACCOUNTS (attempt 1/3)",
                "CREDIT_ACCOUNTS succeeded",
                "starting CONFIRM_WITH_DEPOSITORY (attempt 1/3)",
                "CONFIRM_WITH_DEPOSITORY attempt 1/3 failed (transient): timeout",
                "starting CONFIRM_WITH_DEPOSITORY (attempt 2/3)",
                "CONFIRM_WITH_DEPOSITORY attempt 2/3 failed (transient): timeout",
                "starting CONFIRM_WITH_DEPOSITORY (attempt 3/3)",
                "CONFIRM_WITH_DEPOSITORY succeeded",
            ),
            saga.log.map { it.message },
        )
    }

    @Test
    fun `the log of a crashed and resumed saga shows the interrupted attempt and the resume`() {
        assertFailsWith<SimulatedCrash> {
            orchestrator(ledgerPort = CrashOnceLedger(ledger)).start(event.id)
        }
        // The intent was saved before the action ran, so the crash left it visible.
        assertEquals("starting CREDIT_ACCOUNTS (attempt 1/3)", sagas.find(sagaId)?.log?.last()?.message)

        val resumed = orchestrator().resume(sagaId)

        assertEquals(
            listOf(
                "saga started",
                "starting CALCULATE_ENTITLEMENTS (attempt 1/3)",
                "CALCULATE_ENTITLEMENTS succeeded",
                "starting CREDIT_ACCOUNTS (attempt 1/3)",
                "resumed from saved state AwaitingStep(next=CREDIT_ACCOUNTS)",
                "starting CREDIT_ACCOUNTS (attempt 1/3)",
                "CREDIT_ACCOUNTS succeeded",
                "starting CONFIRM_WITH_DEPOSITORY (attempt 1/3)",
                "CONFIRM_WITH_DEPOSITORY succeeded",
            ),
            resumed.log.map { it.message },
        )
    }

    @Test
    fun `resuming an unknown saga is an error`() {
        assertFailsWith<SagaNotFoundException> { orchestrator().resume(UUID.randomUUID()) }
    }
}