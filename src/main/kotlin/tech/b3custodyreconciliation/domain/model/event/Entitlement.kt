package tech.b3custodyreconciliation.domain.model.event

import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.util.UUID

/** What one investor is owed as a result of one corporate event. */
sealed interface Entitlement {
    val eventId: UUID
    val account: InvestorAccount
}

data class CashEntitlement(
    override val eventId: UUID,
    override val account: InvestorAccount,
    val amount: Money,
) : Entitlement

/**
 * New holding after a share event.
 *
 * [quantityAfter] contains whole shares only (unless a split is applied to an already fractional holding).
 * [residual] is the fraction of a post-event share that could not be credited, truncated to
 * [Quantity.SCALE] places. Residuals are meant to be pooled and handled separately, never silently dropped.
 */
data class ShareEntitlement(
    override val eventId: UUID,
    override val account: InvestorAccount,
    val quantityBefore: Quantity,
    val quantityAfter: Quantity,
    val residual: Quantity,
) : Entitlement
