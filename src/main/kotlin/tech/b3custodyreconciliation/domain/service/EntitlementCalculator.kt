package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.event.BonusShares
import tech.b3custodyreconciliation.domain.model.event.CashEntitlement
import tech.b3custodyreconciliation.domain.model.event.CashEvent
import tech.b3custodyreconciliation.domain.model.event.Rate
import tech.b3custodyreconciliation.domain.model.event.ReverseSplit
import tech.b3custodyreconciliation.domain.model.event.ShareEntitlement
import tech.b3custodyreconciliation.domain.model.event.ShareEvent
import tech.b3custodyreconciliation.domain.model.event.StockSplit
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.math.BigInteger


/**
 * Turns a corporate event plus the holdings at its record date into per-investor entitlements.
 *
 * The caller decides which position types count as eligible and passes the aggregated
 * quantity per account. Results are sorted by account for determinism.
 */
class EntitlementCalculator(
    private val distributor: LargestRemainderDistributor = LargestRemainderDistributor(),
) {

    /**
     * Splits the lump sum actually received from the depository across investors.
     * The shares add up to [receivedTotal] exactly; see [LargestRemainderDistributor].
     */
    fun cashEntitlements(
        event: CashEvent,
        positions: Map<InvestorAccount, Quantity>,
        receivedTotal: Money,
    ): List<CashEntitlement> =
        distributor.distribute(receivedTotal, positions)
            .map { (account, amount) -> CashEntitlement(event.id, account, amount) }

    fun shareEntitlements(
        event: ShareEvent,
        positions: Map<InvestorAccount, Quantity>,
    ): List<ShareEntitlement> =
        positions.toSortedMap().map { (account, before) -> shareEntitlement(event, account, before) }

    private fun shareEntitlement(event: ShareEvent, account: InvestorAccount, before: Quantity): ShareEntitlement =
        when (event) {
            is BonusShares -> bonus(event, account, before)
            is StockSplit -> split(event, account, before)
            is ReverseSplit -> reverse(event, account, before)
        }

    private fun bonus(event: BonusShares, account: InvestorAccount, before: Quantity): ShareEntitlement {
        // before.units is in 1e-6 shares and the rate in 1e-10, so the product is in 1e-16 shares.
        val product = before.units * event.newSharesPerShare.units
        val (wholeBonus, rest) = product.divideAndRemainder(PRODUCT_ONE)
        val residualUnits = rest.divide(RATE_ONE)
        return ShareEntitlement(
            eventId = event.id,
            account = account,
            quantityBefore = before,
            quantityAfter = before + Quantity.ofUnits(wholeBonus * QUANTITY_ONE),
            residual = Quantity.ofUnits(residualUnits),
        )
    }

    private fun split(event: StockSplit, account: InvestorAccount, before: Quantity): ShareEntitlement =
        ShareEntitlement(
            eventId = event.id,
            account = account,
            quantityBefore = before,
            quantityAfter = Quantity.ofUnits(before.units * BigInteger.valueOf(event.factor.toLong())),
            residual = Quantity.ZERO,
        )

    private fun reverse(event: ReverseSplit, account: InvestorAccount, before: Quantity): ShareEntitlement {
        val group = BigInteger.valueOf(event.groupSize.toLong())
        val groupUnits = group * QUANTITY_ONE
        val (whole, leftoverOldUnits) = before.units.divideAndRemainder(groupUnits)
        // Leftover old shares count as leftoverOldUnits / group of a new share (truncated).
        val residualUnits = leftoverOldUnits.divide(group)
        return ShareEntitlement(
            eventId = event.id,
            account = account,
            quantityBefore = before,
            quantityAfter = Quantity.ofUnits(whole * QUANTITY_ONE),
            residual = Quantity.ofUnits(residualUnits),
        )
    }

    private companion object {
        val QUANTITY_ONE: BigInteger = BigInteger.TEN.pow(Quantity.SCALE)
        val RATE_ONE: BigInteger = BigInteger.TEN.pow(Rate.SCALE)
        val PRODUCT_ONE: BigInteger = BigInteger.TEN.pow(Quantity.SCALE + Rate.SCALE)
    }
}