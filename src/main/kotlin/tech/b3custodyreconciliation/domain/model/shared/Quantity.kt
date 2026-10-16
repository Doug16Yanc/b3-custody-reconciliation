package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Non-negative quantity of a security, stored with a fixed scale of [SCALE] decimal places.
 *
 * Normalizing the scale makes equality well-defined (`1.5` equals `1.500000`),
 * which plain [BigDecimal.equals] does not guarantee.
 */
class Quantity private constructor(val value: BigDecimal) : Comparable<Quantity> {

    /** The quantity as an integer count of 10^-[SCALE] units. Used for exact arithmetic. */
    internal val units: BigInteger get() = value.unscaledValue()

    fun isZero(): Boolean = value.signum() == 0

    operator fun plus(other: Quantity): Quantity = Quantity(value.add(other.value))

    operator fun minus(other: Quantity): Quantity {
        val result = value.subtract(other.value)
        if (result.signum() < 0) {
            throw InvalidValueException("Quantity cannot be negative: $value - ${other.value}")
        }
        return Quantity(result)
    }

    /** Signed difference, for reconciliation where either side may be larger. */
    fun signedDifference(other: Quantity): BigDecimal = value.subtract(other.value)

    override fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is Quantity && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toPlainString()

    companion object {
        const val SCALE = 6

        val ZERO = Quantity(BigDecimal.ZERO.setScale(SCALE))

        fun of(value: BigDecimal): Quantity {
            if (value.signum() < 0) {
                throw InvalidValueException("Quantity cannot be negative: $value")
            }
            val normalized = try {
                value.setScale(SCALE, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                throw InvalidValueException("Quantity supports at most $SCALE decimal places: $value", e)
            }
            return Quantity(normalized)
        }

        fun of(value: String): Quantity {
            val parsed = try {
                BigDecimal(value)
            } catch (e: NumberFormatException) {
                throw InvalidValueException("Not a valid quantity: '$value'", e)
            }
            return of(parsed)
        }

        fun of(value: Long): Quantity = of(BigDecimal.valueOf(value))

        /** Builds a quantity from an integer count of 10^-[SCALE] units (the inverse of [units]). */
        internal fun ofUnits(units: BigInteger): Quantity = of(BigDecimal(units, SCALE))
    }
}