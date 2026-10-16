package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException

/**
 * Code of a custody agent (broker or custodian) as known to the depository.
 * Simplified format (1-10 digits); adjust to the real participant-code rules.
 */
@JvmInline
value class AgentCode(val value: String) : Comparable<AgentCode> {

    init {
        if (!FORMAT.matches(value)) throw InvalidValueException("Invalid agent code: '$value'")
    }

    override fun compareTo(other: AgentCode): Int = value.compareTo(other.value)

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("^[0-9]{1,10}$")
    }
}
