package tech.b3custodyreconciliation.application.port.out

import tech.b3custodyreconciliation.domain.model.saga.PayoutSaga
import java.util.UUID

/**
 * Durable home of saga state. The orchestrator saves after every transition,
 * so a crashed process can be resumed from the last saved state.
 *
 * A production implementation must also enforce one saga per event (unique key on eventId)
 * and use optimistic locking so two workers cannot advance the same saga at once.
 */
interface SagaRepository {
    fun save(saga: PayoutSaga)
    fun find(id: UUID): PayoutSaga?
    fun findByEventId(eventId: UUID): PayoutSaga?
}
