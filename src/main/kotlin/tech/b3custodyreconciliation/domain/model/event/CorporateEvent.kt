package tech.b3custodyreconciliation.domain.model.event

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.shared.Isin
import java.time.LocalDate
import java.util.UUID

/**
 * An announced corporate action on one security. Immutable: it describes the announcement only.
 * The progress of paying it out lives in the payout saga, not here.
 *
 * [recordDate] is the date on which holdings are snapshotted to decide who is entitled.
 * [paymentDate] is when cash is paid or new positions take effect.
 */
sealed interface CorporateEvent {
    val id: UUID
    val isin: Isin
    val recordDate: LocalDate
    val paymentDate: LocalDate
}

/** Events that pay cash per share held. */
sealed interface CashEvent : CorporateEvent {
    val grossRatePerShare: Rate
}

/** Events that change how many shares are held. */
sealed interface ShareEvent : CorporateEvent

data class Dividend(
    override val id: UUID,
    override val isin: Isin,
    override val recordDate: LocalDate,
    override val paymentDate: LocalDate,
    override val grossRatePerShare: Rate,
) : CashEvent {
    init {
        requireConsistentDates(recordDate, paymentDate)
    }
}

/** Interest on equity. Cash like a dividend, but taxed differently; tax is applied by a separate policy. */
data class InterestOnEquity(
    override val id: UUID,
    override val isin: Isin,
    override val recordDate: LocalDate,
    override val paymentDate: LocalDate,
    override val grossRatePerShare: Rate,
) : CashEvent {
    init {
        requireConsistentDates(recordDate, paymentDate)
    }
}

/** Bonus issue: every share held earns [newSharesPerShare] new shares (0.10 = 10%). */
data class BonusShares(
    override val id: UUID,
    override val isin: Isin,
    override val recordDate: LocalDate,
    override val paymentDate: LocalDate,
    val newSharesPerShare: Rate,
) : ShareEvent {
    init {
        requireConsistentDates(recordDate, paymentDate)
    }
}

/** Stock split: every share becomes [factor] shares. */
data class StockSplit(
    override val id: UUID,
    override val isin: Isin,
    override val recordDate: LocalDate,
    override val paymentDate: LocalDate,
    val factor: Int,
) : ShareEvent {
    init {
        requireConsistentDates(recordDate, paymentDate)
        if (factor < 2) throw InvalidValueException("Split factor must be at least 2: $factor")
    }
}

/** Reverse split: every [groupSize] shares become one share. */
data class ReverseSplit(
    override val id: UUID,
    override val isin: Isin,
    override val recordDate: LocalDate,
    override val paymentDate: LocalDate,
    val groupSize: Int,
) : ShareEvent {
    init {
        requireConsistentDates(recordDate, paymentDate)
        if (groupSize < 2) throw InvalidValueException("Group size must be at least 2: $groupSize")
    }
}

private fun requireConsistentDates(recordDate: LocalDate, paymentDate: LocalDate) {
    if (paymentDate.isBefore(recordDate)) {
        throw InvalidValueException("Payment date $paymentDate is before record date $recordDate")
    }
}
