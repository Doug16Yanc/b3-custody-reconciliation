package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException

/**
 * Identifier of an investor's custody account.
 * Comparable so that distributions can break ties deterministically.
 */
@JvmInline
value class InvestorAccount(val value: String) : Comparable<InvestorAccount> {

    init {
        if (!FORMAT.matches(value)) throw InvalidValueException("Invalid investor account: '$value'")
    }

    override fun compareTo(other: InvestorAccount): Int = value.compareTo(other.value)

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("^[A-Za-z0-9-]{1,20}$")
    }
}
