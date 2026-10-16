package tech.b3custodyreconciliation.infrastructure.inmemory

import tech.b3custodyreconciliation.application.port.out.EntitlementPort
import tech.b3custodyreconciliation.application.port.out.RejectedException
import tech.b3custodyreconciliation.domain.model.event.CashEntitlement
import tech.b3custodyreconciliation.domain.model.event.CashEvent
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import tech.b3custodyreconciliation.domain.service.EntitlementCalculator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Calculates and keeps entitlements per saga. Idempotent: recalculating an existing saga is a no-op. */
class InMemoryEntitlementStore(
    private val event: CashEvent,
    private val positions: Map<InvestorAccount, Quantity>,
    private val receivedTotal: Money,
    private val calculator: EntitlementCalculator = EntitlementCalculator(),
) : EntitlementPort {

    private val bySaga = ConcurrentHashMap<UUID, List<CashEntitlement>>()

    override fun calculate(sagaId: UUID, eventId: UUID) {
        if (eventId != event.id) throw RejectedException("Unknown corporate event $eventId")
        bySaga.computeIfAbsent(sagaId) { calculator.cashEntitlements(event, positions, receivedTotal) }
    }

    override fun discard(sagaId: UUID) {
        bySaga.remove(sagaId)
    }

    fun find(sagaId: UUID): List<CashEntitlement>? = bySaga[sagaId]
}
