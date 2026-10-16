package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.position.PositionRecord
import tech.b3custodyreconciliation.domain.model.position.PositionType
import tech.b3custodyreconciliation.domain.model.reconciliation.BreakType
import tech.b3custodyreconciliation.domain.model.shared.AgentCode
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PositionReconcilerTest {

    private var counter = 0L
    private val reconciler = PositionReconciler(idGenerator = { UUID(0L, ++counter) })

    private val agent = AgentCode("123")
    private val date = LocalDate.of(2026, 10, 12)
    private val petr = Isin("BRPETRACNPR6")
    private val vale = Isin("BRVALEACNOR0")

    private fun record(account: String, isin: Isin, quantity: Long, type: PositionType = PositionType.FREE) =
        PositionRecord(InvestorAccount(account), isin, type, Quantity.of(quantity))

    private fun reconcile(depository: List<PositionRecord>, ledger: List<PositionRecord>) =
        reconciler.reconcile(date, agent, depository, ledger)

    @Test
    fun `identical sides produce no breaks`() {
        val positions = listOf(record("A", petr, 100), record("B", vale, 50))
        assertTrue(reconcile(positions, positions).isEmpty())
    }

    @Test
    fun `detects a quantity mismatch`() {
        val breaks = reconcile(listOf(record("A", petr, 100)), listOf(record("A", petr, 90)))
        assertEquals(1, breaks.size)
        assertEquals(BreakType.QUANTITY_MISMATCH, breaks.single().type)
    }

    @Test
    fun `detects a position missing on either side`() {
        val breaks = reconcile(
            depository = listOf(record("A", petr, 100)),
            ledger = listOf(record("B", vale, 10)),
        )
        assertEquals(
            listOf(BreakType.MISSING_INTERNALLY, BreakType.MISSING_AT_DEPOSITORY),
            breaks.map { it.type },
        )
    }

    @Test
    fun `a zero quantity is the same as an absent position`() {
        assertTrue(reconcile(listOf(record("A", petr, 0)), emptyList()).isEmpty())
        assertTrue(reconcile(emptyList(), listOf(record("A", petr, 0))).isEmpty())
    }

    @Test
    fun `free versus blocked is reported as two breaks, one per position type`() {
        val breaks = reconcile(
            depository = listOf(record("A", petr, 100, PositionType.FREE)),
            ledger = listOf(record("A", petr, 100, PositionType.BLOCKED)),
        )
        assertEquals(
            listOf(PositionType.FREE to BreakType.MISSING_INTERNALLY, PositionType.BLOCKED to BreakType.MISSING_AT_DEPOSITORY),
            breaks.map { it.key.type to it.type },
        )
    }

    @Test
    fun `rejects duplicate position lines on one side`() {
        val duplicated = listOf(record("A", petr, 1), record("A", petr, 2))
        assertFailsWith<InvalidValueException> { reconcile(duplicated, emptyList()) }
        assertFailsWith<InvalidValueException> { reconcile(emptyList(), duplicated) }
    }

    @Test
    fun `output order does not depend on input order`() {
        val depository = (1..30).map { record("ACC-%02d".format(it), petr, it.toLong()) }
        val ledger = (1..30).map { record("ACC-%02d".format(it), petr, it.toLong() + 1) }

        val first = reconcile(depository, ledger).map { it.key }
        val second = reconcile(depository.shuffled(Random(1)), ledger.shuffled(Random(2))).map { it.key }

        assertEquals(30, first.size)
        assertEquals(first, second)
        assertEquals(first.sorted(), first)
    }
}
