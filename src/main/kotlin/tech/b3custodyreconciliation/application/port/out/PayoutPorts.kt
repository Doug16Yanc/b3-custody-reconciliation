package tech.b3custodyreconciliation.application.port.out

import java.util.UUID

/*
 * Every method below MUST be idempotent: calling it twice with the same sagaId has the same
 * effect as calling it once. The orchestrator gives at-least-once execution (a crash between
 * "action done" and "state saved" means the action runs again on resume), and idempotent
 * ports are what turn that into effectively-once.
 *
 * Throw RejectedException for a definitive refusal and TransientFailureException for a
 * failure that may go away on retry.
 */

interface EntitlementPort {
    fun calculate(sagaId: UUID, eventId: UUID)
    fun discard(sagaId: UUID)
}

interface LedgerPort {
    fun credit(sagaId: UUID)

    /** Undoes [credit] by appending opposite entries. Never deletes history. */
    fun reverse(sagaId: UUID)
}

interface DepositoryPort {
    fun confirm(sagaId: UUID, eventId: UUID)
}
