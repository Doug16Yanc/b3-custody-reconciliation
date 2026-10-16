package tech.b3custodyreconciliation.application.demo

import tech.b3custodyreconciliation.application.orchestrator.PayoutSagaOrchestrator
import tech.b3custodyreconciliation.application.port.out.LedgerPort
import tech.b3custodyreconciliation.application.port.out.RejectedException
import tech.b3custodyreconciliation.application.port.out.TransientFailureException
import tech.b3custodyreconciliation.domain.model.event.Dividend
import tech.b3custodyreconciliation.domain.model.event.Rate
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import tech.b3custodyreconciliation.infrastructure.inmemory.InMemoryDepository
import tech.b3custodyreconciliation.infrastructure.inmemory.InMemoryEntitlementStore
import tech.b3custodyreconciliation.infrastructure.inmemory.InMemoryLedger
import tech.b3custodyreconciliation.infrastructure.inmemory.InMemorySagaRepository
import tech.b3custodyreconciliation.domain.model.saga.PayoutSaga
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/*
 * Four runs of the same payout saga: R$ 10.00 of dividends split across three investors holding one share each.
 * Run it from the IDE (main function in SagaDemo.kt). No framework, no I/O, no database.
 */

private val holders = mapOf(
    InvestorAccount("ACC-A") to Quantity.of(1),
    InvestorAccount("ACC-B") to Quantity.of(1),
    InvestorAccount("ACC-C") to Quantity.of(1),
)

private class SimulatedCrash : RuntimeException("process died after crediting, before saving the result")

/** Credits the accounts, then dies once, before the orchestrator can record the success. */
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

private class World(depositoryScript: (Int) -> Unit = {}, crashAfterCredit: Boolean = false) {
    val sagaId: UUID = UUID.randomUUID()
    val event = Dividend(
        UUID.randomUUID(), Isin("BRPETRACNPR6"),
        LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 5), Rate.of("3.3333333333"),
    )
    val sagas = InMemorySagaRepository()
    val store = InMemoryEntitlementStore(event, holders, Money(1_000))
    val ledger = InMemoryLedger(store)
    val depository = InMemoryDepository(depositoryScript)
    private val ledgerPort: LedgerPort = if (crashAfterCredit) CrashOnceLedger(ledger) else ledger
    val orchestrator = PayoutSagaOrchestrator(
        sagas, store, ledgerPort, depository, idGenerator = { sagaId },
    )
}

private fun brl(cents: Long): String =
    String.format(Locale.ROOT, "BRL %s%d.%02d", if (cents < 0) "-" else "", abs(cents) / 100, abs(cents) % 100)

private fun report(world: World, saga: PayoutSaga) {
    saga.log.forEach { println("   - ${it.message}") }
    println("   final state: ${saga.state}")
    world.ledger.entries().forEach { println("   ledger: ${it.kind} ${it.account} ${brl(it.cents)}") }
    holders.keys.sorted().forEach { println("   balance ${it}: ${brl(world.ledger.balanceOf(it))}") }
    println()
}

private fun happyPath() {
    println("== 1. Happy path ==")
    val world = World()
    report(world, world.orchestrator.start(world.event.id))
}

private fun depositoryRejects() {
    println("== 2. The depository rejects: credits are reversed ==")
    val world = World(depositoryScript = { throw RejectedException("depository rejected: ISIN suspended") })
    report(world, world.orchestrator.start(world.event.id))
}

private fun transientFailuresAreRetried() {
    println("== 3. Two timeouts, then success: retried, no compensation ==")
    val world = World(depositoryScript = { attempt ->
        if (attempt < 3) throw TransientFailureException("depository timeout (call $attempt)")
    })
    val saga = world.orchestrator.start(world.event.id)
    println("   depository was called ${world.depository.callCount} times")
    report(world, saga)
}

private fun crashAndResume() {
    println("== 4. Crash after crediting, then resume: credited exactly once ==")
    val world = World(crashAfterCredit = true)
    try {
        world.orchestrator.start(world.event.id)
    } catch (e: SimulatedCrash) {
        println("   !! ${e.message}")
    }
    println("   saved state after the crash: ${world.sagas.find(world.sagaId)?.state}")
    println("   ledger entries already written: ${world.ledger.entries().size}")
    println("   -- restarting and resuming --")
    report(world, world.orchestrator.resume(world.sagaId))
}

fun main() {
    happyPath()
    depositoryRejects()
    transientFailuresAreRetried()
    crashAndResume()
}
