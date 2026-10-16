package tech.b3custodyreconciliation.domain.exception

/** Root of all exceptions raised by the domain layer. */
open class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A value object or entity was constructed with data that violates its structural rules. */
class InvalidValueException(message: String, cause: Throwable? = null) : DomainException(message, cause)

/** A business invariant (e.g. conservation of money) did not hold after an operation. */
class InvariantViolationException(message: String) : DomainException(message)

/** A state machine was asked to make a transition that its current state does not allow. */
class IllegalStateTransitionException(entity: String, from: String, attempted: String) :
    DomainException("Illegal $entity transition from $from: $attempted")
