package tech.b3custodyreconciliation.domain.model.reconciliation

import tech.b3custodyreconciliation.domain.exception.IllegalStateTransitionException
import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.position.PositionKey
import tech.b3custodyreconciliation.domain.model.shared.AgentCode
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A disagreement between the depository and our ledger on one position line.
 *
 * Immutable: every transition returns a new instance with the audit trail extended.
 * Time is always passed in, never read from the system clock, so behaviour stays deterministic.
 * A null quantity means "the position is absent on that side".
 */
class ReconciliationBreak private constructor(
    val id: UUID,
    val businessDate: LocalDate,
    val agent: AgentCode,
    val key: PositionKey,
    val depositoryQuantity: Quantity?,
    val ledgerQuantity: Quantity?,
    val status: BreakStatus,
    val history: List<BreakTransition>,
) {

    init {
        if (depositoryQuantity == null && ledgerQuantity == null) {
            throw InvalidValueException("A break needs a position on at least one side")
        }
        if (depositoryQuantity == ledgerQuantity) {
            throw InvalidValueException("Both sides agree on $depositoryQuantity: that is not a break")
        }
    }

    val type: BreakType
        get() = when {
            ledgerQuantity == null -> BreakType.MISSING_INTERNALLY
            depositoryQuantity == null -> BreakType.MISSING_AT_DEPOSITORY
            else -> BreakType.QUANTITY_MISMATCH
        }

    /** Depository minus ledger. Positive means the depository holds more than we think. */
    val difference: BigDecimal
        get() = (depositoryQuantity ?: Quantity.ZERO).signedDifference(ledgerQuantity ?: Quantity.ZERO)

    fun startReview(analyst: String, at: Instant): ReconciliationBreak {
        requireText(analyst, "analyst")
        val target = BreakStatus.UnderReview(analyst)
        if (status !is BreakStatus.Open) throw illegal(target)
        return transitionTo(target, at, analyst)
    }

    fun markAdjusted(reason: String, by: String, at: Instant): ReconciliationBreak {
        requireText(reason, "reason")
        requireText(by, "actor")
        val target = BreakStatus.Adjusted(reason)
        if (status !is BreakStatus.UnderReview && status !is BreakStatus.Disputed) throw illegal(target)
        return transitionTo(target, at, by)
    }

    fun dispute(reference: String, by: String, at: Instant): ReconciliationBreak {
        requireText(reference, "reference")
        requireText(by, "actor")
        val target = BreakStatus.Disputed(reference)
        if (status !is BreakStatus.UnderReview) throw illegal(target)
        return transitionTo(target, at, by)
    }

    fun resolve(by: String, at: Instant): ReconciliationBreak {
        requireText(by, "actor")
        if (status !is BreakStatus.Disputed) throw illegal(BreakStatus.Resolved)
        return transitionTo(BreakStatus.Resolved, at, by)
    }

    private fun transitionTo(next: BreakStatus, at: Instant, actor: String) = ReconciliationBreak(
        id, businessDate, agent, key, depositoryQuantity, ledgerQuantity,
        next, history + BreakTransition(status, next, at, actor),
    )

    private fun illegal(target: BreakStatus) =
        IllegalStateTransitionException("ReconciliationBreak", status.code, "-> ${target.code}")

    private fun requireText(value: String, field: String) {
        if (value.isBlank()) throw InvalidValueException("$field must not be blank")
    }

    override fun equals(other: Any?): Boolean = other is ReconciliationBreak && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ReconciliationBreak($id, $key, $type, ${status.code})"

    companion object {
        /** Creates a newly detected break in the [BreakStatus.Open] state. */
        fun detected(
            id: UUID,
            businessDate: LocalDate,
            agent: AgentCode,
            key: PositionKey,
            depositoryQuantity: Quantity?,
            ledgerQuantity: Quantity?,
        ): ReconciliationBreak = ReconciliationBreak(
            id, businessDate, agent, key, depositoryQuantity, ledgerQuantity, BreakStatus.Open, emptyList(),
        )
    }
}
