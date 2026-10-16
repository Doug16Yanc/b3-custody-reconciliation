package tech.b3custodyreconciliation.domain.model.position

/** How a position is held. The depository reports each type separately, so we reconcile each separately. */
enum class PositionType {
    /** Freely available. */
    FREE,

    /** Encumbered, e.g. pledged as collateral. */
    BLOCKED,

    /** Lent out through securities lending. */
    LENT,
}
