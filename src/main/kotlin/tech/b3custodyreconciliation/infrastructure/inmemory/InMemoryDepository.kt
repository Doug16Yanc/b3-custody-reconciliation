package tech.b3custodyreconciliation.infrastructure.inmemory

import tech.b3custodyreconciliation.application.port.out.DepositoryPort
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for the depository. [script] runs on every call with the 1-based call number,
 * and can throw RejectedException or TransientFailureException to simulate the depository's behaviour.
 */
class InMemoryDepository(private val script: (attempt: Int) -> Unit = {}) : DepositoryPort {

    private val calls = AtomicInteger()

    val callCount: Int get() = calls.get()

    override fun confirm(sagaId: UUID, eventId: UUID) {
        script(calls.incrementAndGet())
    }
}
