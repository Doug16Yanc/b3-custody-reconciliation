package tech.b3custodyreconciliation.infrastructure.inmemory

import tech.b3custodyreconciliation.application.port.out.SagaRepository
import tech.b3custodyreconciliation.domain.model.saga.PayoutSaga
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemorySagaRepository : SagaRepository {

    private val store = ConcurrentHashMap<UUID, PayoutSaga>()

    override fun save(saga: PayoutSaga) {
        store[saga.id] = saga
    }

    override fun find(id: UUID): PayoutSaga? = store[id]

    override fun findByEventId(eventId: UUID): PayoutSaga? = store.values.firstOrNull { it.eventId == eventId }
}
