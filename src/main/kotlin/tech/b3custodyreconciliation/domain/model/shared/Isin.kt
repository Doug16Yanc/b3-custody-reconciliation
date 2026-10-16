package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException

/**
 * International Securities Identification Number (ISO 6166).
 *
 * Structure: 2-letter country code + 9 alphanumeric characters + 1 check digit.
 * The check digit is validated with the Luhn algorithm applied to the
 * letter-expanded form of the code (A=10 ... Z=35).
 */
@JvmInline
value class Isin(val value: String) {

    init {
        if (!FORMAT.matches(value)) {
            throw InvalidValueException("Invalid ISIN format: '$value'")
        }
        if (!hasValidCheckDigit(value)) {
            throw InvalidValueException("Invalid ISIN check digit: '$value'")
        }
    }

    val countryCode: String get() = value.substring(0, 2)

    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")

        /** Lenient entry point for external input: trims and upper-cases before validating. */
        fun parse(raw: String): Isin = Isin(raw.trim().uppercase())

        private fun hasValidCheckDigit(isin: String): Boolean {
            val expanded = isin.map { Character.digit(it, 36).toString() }.joinToString("")
            var sum = 0
            expanded.reversed().forEachIndexed { index, char ->
                var digit = char.digitToInt()
                if (index % 2 == 1) {
                    digit *= 2
                    if (digit > 9) digit -= 9
                }
                sum += digit
            }
            return sum % 10 == 0
        }
    }
}
