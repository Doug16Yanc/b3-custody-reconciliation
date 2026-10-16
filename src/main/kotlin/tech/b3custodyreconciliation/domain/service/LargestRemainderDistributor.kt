package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.exception.InvariantViolationException
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import tech.b3custodyreconciliation.domain.model.shared.total
import java.math.BigInteger
import java.util.SortedMap

/**
 * Splits a payout across investors proportionally to the quantity they held,
 * using the largest-remainder (Hamilton) method.
 *
 * Guarantees:
 *  1. Conservation: the sum of all shares equals the total exactly.
 *  2. Fairness: every share is either the floor or the floor + 1 cent of its exact value.
 *  3. Determinism: ties on the remainder are broken by account id, so the result
 *     never depends on the iteration order of the input.
 *
 * All arithmetic is done in exact integers; no floating point is involved.
 */
class LargestRemainderDistributor {

    fun distribute(total: Money, positions: Map<InvestorAccount, Quantity>): SortedMap<InvestorAccount, Money> {
        if (positions.isEmpty()) {
            throw InvalidValueException("Cannot distribute among zero positions")
        }
        val totalUnits = positions.values.fold(BigInteger.ZERO) { acc, quantity -> acc + quantity.units }
        if (totalUnits.signum() == 0) {
            throw InvalidValueException("Total quantity must be positive")
        }
        val totalCents = BigInteger.valueOf(total.cents)

        val shares = positions.map { (account, quantity) ->
            val (floorCents, remainder) = (totalCents * quantity.units).divideAndRemainder(totalUnits)
            Share(account, floorCents.longValueExact(), remainder)
        }

        val leftoverCents = total.cents - shares.sumOf { it.floorCents }
        if (leftoverCents < 0 || leftoverCents > shares.size) {
            throw InvariantViolationException("Unexpected leftover of $leftoverCents cents across ${shares.size} shares")
        }

        val winners = shares
            .sortedWith(compareByDescending<Share> { it.remainder }.thenBy { it.account })
            .take(leftoverCents.toInt())
            .map { it.account }
            .toSet()

        val result = shares
            .associate { it.account to Money(it.floorCents + if (it.account in winners) 1 else 0) }
            .toSortedMap()

        if (result.values.total() != total) {
            throw InvariantViolationException("Distribution does not conserve the total of $total")
        }
        return result
    }

    private data class Share(val account: InvestorAccount, val floorCents: Long, val remainder: BigInteger)
}