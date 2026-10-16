package tech.b3custodyreconciliation.domain.model.position

import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount

/** Identity of a position line: one account, one security, one holding type. */
data class PositionKey(
    val account: InvestorAccount,
    val isin: Isin,
    val type: PositionType,
) : Comparable<PositionKey> {

    override fun compareTo(other: PositionKey): Int = COMPARATOR.compare(this, other)

    private companion object {
        val COMPARATOR: Comparator<PositionKey> =
            compareBy<PositionKey>({ it.account }, { it.isin.value }, { it.type })
    }
}
