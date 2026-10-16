package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Non-negative amount in Brazilian reais (BRL), stored as an integer number of cents.
 *
 * Working in minor units removes floating-point error and makes the
 * largest-remainder distribution exact. Single-currency by design:
 * add a currency field if the domain ever needs more.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {

    init {
        if (cents < 0) throw InvalidValueException("Money cannot be negative: $cents cents")
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(cents, other.cents))

    operator fun minus(other: Money): Money {
        if (other.cents > cents) {
            throw InvalidValueException("Money cannot be negative: $cents - ${other.cents} cents")
        }
        return Money(cents - other.cents)
    }

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    override fun toString(): String = String.format(Locale.ROOT, "BRL %d.%02d", cents / 100, cents % 100)

    companion object {
        val ZERO = Money(0)

        /** Converts a decimal amount in reais; rejects sub-cent precision instead of rounding it away. */
        fun ofReais(amount: BigDecimal): Money {
            val inCents = try {
                amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
            } catch (e: ArithmeticException) {
                throw InvalidValueException("Amount must have at most 2 decimal places: $amount", e)
            }
            return Money(inCents)
        }
    }
}

fun Iterable<Money>.total(): Money = fold(Money.ZERO) { acc, money -> acc + money }
