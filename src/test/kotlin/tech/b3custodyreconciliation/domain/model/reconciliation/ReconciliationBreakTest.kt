package tech.b3custodyreconciliation.domain.model.reconciliation

import tech.b3custodyreconciliation.domain.exception.IllegalStateTransitionException
import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.position.PositionKey
import tech.b3custodyreconciliation.domain.model.position.PositionType
import tech.b3custodyreconciliation.domain.model.shared.AgentCode
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconciliationBreakTest {

    private val key = PositionKey(InvestorAccount("ACC-1"), Isin("BRPETRACNPR6"), PositionType.FREE)
    private val agent = AgentCode("123")
    private val date = LocalDate.of(2026, 10, 12)
    private val t0 = Instant.parse("2026-10-12T10:00:00Z")
    private val t1 = t0.plusSeconds(60)
    private val t2 = t0.plusSeconds(120)

    private fun newBreak(depository: Quantity? = Quantity.of(100), ledger: Quantity? = Quantity.of(90)) =
        ReconciliationBreak.detected(UUID.randomUUID(), date, agent, key, depository, ledger)

    @Test
    fun `derives the break type from which sides hold the position`() {
        assertEquals(BreakType.QUANTITY_MISMATCH, newBreak().type)
        assertEquals(BreakType.MISSING_INTERNALLY, newBreak(ledger = null).type)
        assertEquals(BreakType.MISSING_AT_DEPOSITORY, newBreak(depository = null).type)
    }

    @Test
    fun `difference is depository minus ledger`() {
        assertEquals(BigDecimal("10.000000"), newBreak().difference)
        assertEquals(BigDecimal("100.000000"), newBreak(ledger = null).difference)
        assertEquals(BigDecimal("-90.000000"), newBreak(depository = null).difference)
    }

    @Test
    fun `rejects a break where both sides agree or both are absent`() {
        assertFailsWith<InvalidValueException> { newBreak(Quantity.of(5), Quantity.of(5)) }
        assertFailsWith<InvalidValueException> { newBreak(null, null) }
    }

    @Test
    fun `starts open with an empty history`() {
        val b = newBreak()
        assertEquals(BreakStatus.Open, b.status)
        assertTrue(b.history.isEmpty())
    }

    @Test
    fun `review, dispute and resolve records every transition`() {
        val resolved = newBreak()
            .startReview("ana", t0)
            .dispute("PROTOCOL-77", "ana", t1)
            .resolve("ana", t2)

        assertEquals(BreakStatus.Resolved, resolved.status)
        assertTrue(resolved.status.isTerminal)
        assertEquals(
            listOf(
                BreakTransition(BreakStatus.Open, BreakStatus.UnderReview("ana"), t0, "ana"),
                BreakTransition(BreakStatus.UnderReview("ana"), BreakStatus.Disputed("PROTOCOL-77"), t1, "ana"),
                BreakTransition(BreakStatus.Disputed("PROTOCOL-77"), BreakStatus.Resolved, t2, "ana"),
            ),
            resolved.history,
        )
    }

    @Test
    fun `can be adjusted straight from review`() {
        val adjusted = newBreak().startReview("ana", t0).markAdjusted("late booking", "ana", t1)
        assertEquals(BreakStatus.Adjusted("late booking"), adjusted.status)
        assertTrue(adjusted.status.isTerminal)
    }

    @Test
    fun `a rejected dispute can still end in an adjustment`() {
        val adjusted = newBreak()
            .startReview("ana", t0)
            .dispute("PROTOCOL-77", "ana", t1)
            .markAdjusted("dispute rejected", "ana", t2)
        assertEquals(BreakStatus.Adjusted("dispute rejected"), adjusted.status)
    }

    @Test
    fun `rejects transitions that skip the review`() {
        val open = newBreak()
        assertFailsWith<IllegalStateTransitionException> { open.markAdjusted("x", "ana", t0) }
        assertFailsWith<IllegalStateTransitionException> { open.dispute("x", "ana", t0) }
        assertFailsWith<IllegalStateTransitionException> { open.resolve("ana", t0) }
    }

    @Test
    fun `terminal states accept no further transitions`() {
        val adjusted = newBreak().startReview("ana", t0).markAdjusted("x", "ana", t1)
        assertFailsWith<IllegalStateTransitionException> { adjusted.startReview("bob", t2) }
        assertFailsWith<IllegalStateTransitionException> { adjusted.dispute("x", "bob", t2) }

        val resolved = newBreak().startReview("ana", t0).dispute("x", "ana", t1).resolve("ana", t2)
        assertFailsWith<IllegalStateTransitionException> { resolved.markAdjusted("x", "bob", t2) }
        assertFailsWith<IllegalStateTransitionException> { resolved.resolve("bob", t2) }
    }

    @Test
    fun `transitions never mutate the original`() {
        val original = newBreak()
        original.startReview("ana", t0)
        assertEquals(BreakStatus.Open, original.status)
        assertTrue(original.history.isEmpty())
        assertFalse(original.status.isTerminal)
    }

    @Test
    fun `rejects blank actors and texts`() {
        val b = newBreak()
        assertFailsWith<InvalidValueException> { b.startReview(" ", t0) }
        val reviewed = b.startReview("ana", t0)
        assertFailsWith<InvalidValueException> { reviewed.markAdjusted("", "ana", t1) }
        assertFailsWith<InvalidValueException> { reviewed.dispute("ref", "", t1) }
    }
}
