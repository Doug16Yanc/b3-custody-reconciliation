package tech.b3custodyreconciliation.infrastructure.inmemory

import tech.b3custodyreconciliation.application.port.out.LedgerPort
import tech.b3custodyreconciliation.application.port.out.RejectedException
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import java.util.UUID

enum class LedgerEntryKind { CREDIT, REVERSAL }

/** One immutable line of the ledger. [cents] is signed: a reversal is the negative of its credit. */
data class LedgerEntry(
    val sagaId: UUID,
    val account: InvestorAccount,
    val cents: Long,
    val kind: LedgerEntryKind,
)

/**
 * Append-only ledger. Compensation never deletes: it appends opposite entries, so the full history
 * stays visible. Both operations are idempotent per saga.
 */
class InMemoryLedger(private val entitlements: InMemoryEntitlementStore) : LedgerPort {

    private val log = mutableListOf<LedgerEntry>()

    @Synchronized
    override fun credit(sagaId: UUID) {
        if (log.any { it.sagaId == sagaId && it.kind == LedgerEntryKind.CREDIT }) return
        val owed = entitlements.find(sagaId) ?: throw RejectedException("No entitlements for saga $sagaId")
        owed.forEach { log.add(LedgerEntry(sagaId, it.account, it.amount.cents, LedgerEntryKind.CREDIT)) }
    }

    @Synchronized
    override fun reverse(sagaId: UUID) {
        if (log.any { it.sagaId == sagaId && it.kind == LedgerEntryKind.REVERSAL }) return
        val credits = log.filter { it.sagaId == sagaId && it.kind == LedgerEntryKind.CREDIT }
        credits.forEach { log.add(LedgerEntry(sagaId, it.account, -it.cents, LedgerEntryKind.REVERSAL)) }
    }

    @Synchronized
    fun entries(): List<LedgerEntry> = log.toList()

    @Synchronized
    fun balanceOf(account: InvestorAccount): Long = log.filter { it.account == account }.sumOf { it.cents }
}
