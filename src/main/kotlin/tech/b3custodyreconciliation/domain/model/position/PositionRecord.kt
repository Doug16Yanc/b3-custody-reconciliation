package tech.b3custodyreconciliation.domain.model.position

import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Quantity

/** A quantity held on one position line, as reported by either the depository or our own ledger. */
data class PositionRecord(val key: PositionKey, val quantity: Quantity) {

    constructor(account: InvestorAccount, isin: Isin, type: PositionType, quantity: Quantity) :
        this(PositionKey(account, isin, type), quantity)
}
