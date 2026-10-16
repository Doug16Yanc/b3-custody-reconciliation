package tech.b3custodyreconciliation.domain.model.reconciliation

import java.time.Instant

/** One entry of a break's audit trail. */
data class BreakTransition(
    val from: BreakStatus,
    val to: BreakStatus,
    val at: Instant,
    val actor: String,
)
