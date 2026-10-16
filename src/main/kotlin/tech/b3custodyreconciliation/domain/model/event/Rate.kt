package tech.b3custodyreconciliation.domain.model.event

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Strictly positive decimal with a fixed scale of [SCALE] places, used for per-share rates.
 * The owning field gives it meaning: BRL per share for cash events, new shares per share held for bonus issues.
 */
class Rate private constructor(val value: BigDecimal) : Comparable<Rate> {

    /** The rate as an integer count of 10^-[SCALE] units. */
    internal val units: BigInteger get() = value.unscaledValue()

    override fun compareTo(other: Rate): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is Rate && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toPlainString()

    companion object {
        const val SCALE = 10

        fun of(value: BigDecimal): Rate {
            if (value.signum() <= 0) throw InvalidValueException("Rate must be positive: $value")
            val normalized = try {
                value.setScale(SCALE, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                throw InvalidValueException("Rate supports at most $SCALE decimal places: $value", e)
            }
            return Rate(normalized)
        }

        fun of(value: String): Rate {
            val parsed = try {
                BigDecimal(value)
            } catch (e: NumberFormatException) {
                throw InvalidValueException("Not a valid rate: '$value'", e)
            }
            return of(parsed)
        }
    }
}
