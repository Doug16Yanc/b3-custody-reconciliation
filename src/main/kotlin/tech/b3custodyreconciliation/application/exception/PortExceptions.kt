package tech.b3custodyreconciliation.application.port.out

/**
 * The collaborator understood the request and refused it.
 * Retrying cannot help, so the saga must start compensating.
 */
class RejectedException(message: String) : RuntimeException(message)

/**
 * The collaborator could not be reached or timed out.
 * The very same request may succeed if it is retried.
 */
class TransientFailureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
