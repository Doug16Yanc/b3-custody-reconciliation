package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.position.PositionKey
import tech.b3custodyreconciliation.domain.model.position.PositionRecord
import tech.b3custodyreconciliation.domain.model.reconciliation.ReconciliationBreak
import tech.b3custodyreconciliation.domain.model.shared.AgentCode
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.time.LocalDate
import java.util.UUID

/**
 * Compares the depository's positions with our ledger and reports every disagreement.
 *
 * This is the in-memory, domain-level twin of the SQL FULL OUTER JOIN used at scale:
 * it defines the rules, and the bulk implementation must produce the same breaks.
 *
 *  - A zero quantity is treated as "absent", so a zero record never creates a break.
 *  - Each side must contain a position line at most once.
 *  - Breaks come back sorted by [tech.custodyreconciliation.domain.model.position.PositionKey], so the output never depends on input order.
 */
class PositionReconciler(
    private val idGenerator: () -> UUID = { UUID.randomUUID() },
) {

    fun reconcile(
        businessDate: LocalDate,
        agent: AgentCode,
        depository: Collection<PositionRecord>,
        ledger: Collection<PositionRecord>,
    ): List<ReconciliationBreak> {
        val depositoryByKey = index(depository, "depository")
        val ledgerByKey = index(ledger, "ledger")
        val keys = (depositoryByKey.keys + ledgerByKey.keys).toSortedSet()

        return keys.mapNotNull { key ->
            val depositoryQuantity = depositoryByKey[key]
            val ledgerQuantity = ledgerByKey[key]
            if (depositoryQuantity == ledgerQuantity) {
                null
            } else {
                ReconciliationBreak.detected(
                    idGenerator(), businessDate, agent, key, depositoryQuantity, ledgerQuantity,
                )
            }
        }
    }

    private fun index(records: Collection<PositionRecord>, source: String): Map<PositionKey, Quantity> {
        val result = HashMap<PositionKey, Quantity>()
        for (record in records) {
            if (record.quantity.isZero()) continue
            if (result.put(record.key, record.quantity) != null) {
                throw InvalidValueException("Duplicate position line in $source: ${record.key}")
            }
        }
        return result
    }
}