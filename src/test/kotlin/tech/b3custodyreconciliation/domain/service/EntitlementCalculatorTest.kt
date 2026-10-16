package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.model.event.BonusShares
import tech.b3custodyreconciliation.domain.model.event.CashEntitlement
import tech.b3custodyreconciliation.domain.model.event.Dividend
import tech.b3custodyreconciliation.domain.model.event.Rate
import tech.b3custodyreconciliation.domain.model.event.ReverseSplit
import tech.b3custodyreconciliation.domain.model.event.StockSplit
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Isin
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitlementCalculatorTest {

    private val calculator = EntitlementCalculator()

    private val isin = Isin("BRPETRACNPR6")
    private val recordDate = LocalDate.of(2026, 10, 20)
    private val paymentDate = LocalDate.of(2026, 11, 5)

    private val a = InvestorAccount("A")
    private val b = InvestorAccount("B")
    private val c = InvestorAccount("C")

    private fun bonus(ratio: String) =
        BonusShares(UUID.randomUUID(), isin, recordDate, paymentDate, Rate.of(ratio))

    private fun single(event: BonusShares, quantity: Quantity) =
        calculator.shareEntitlements(event, mapOf(a to quantity)).single()

    // ---- cash ----

    @Test
    fun `cash entitlements add up exactly to the amount received`() {
        val event = Dividend(UUID.randomUUID(), isin, recordDate, paymentDate, Rate.of("0.333"))
        val result = calculator.cashEntitlements(
            event,
            mapOf(c to Quantity.of(1), a to Quantity.of(1), b to Quantity.of(1)),
            Money(100),
        )
        assertEquals(
            listOf(
                CashEntitlement(event.id, a, Money(34)),
                CashEntitlement(event.id, b, Money(33)),
                CashEntitlement(event.id, c, Money(33)),
            ),
            result,
        )
    }

    // ---- bonus ----

    @Test
    fun `bonus with no fraction`() {
        val result = single(bonus("0.1"), Quantity.of(100))
        assertEquals(Quantity.of(110), result.quantityAfter)
        assertEquals(Quantity.ZERO, result.residual)
    }

    @Test
    fun `bonus keeps the fraction as a residual instead of truncating it away`() {
        val result = single(bonus("0.1"), Quantity.of(15))
        assertEquals(Quantity.of(16), result.quantityAfter)
        assertEquals(Quantity.of("0.5"), result.residual)
    }

    @Test
    fun `bonus residual is truncated to six places`() {
        val result = single(bonus("0.3333333333"), Quantity.of(7))
        assertEquals(Quantity.of(9), result.quantityAfter)
        assertEquals(Quantity.of("0.333333"), result.residual)
    }

    @Test
    fun `a holder too small to earn a whole bonus share keeps everything as residual`() {
        val result = single(bonus("0.1"), Quantity.of(3))
        assertEquals(Quantity.of(3), result.quantityAfter)
        assertEquals(Quantity.of("0.3"), result.residual)
    }

    // ---- split ----

    @Test
    fun `split multiplies the holding and leaves no residual`() {
        val event = StockSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 2)
        val result = calculator.shareEntitlements(event, mapOf(a to Quantity.of(50))).single()
        assertEquals(Quantity.of(100), result.quantityAfter)
        assertEquals(Quantity.ZERO, result.residual)
    }

    // ---- reverse split ----

    @Test
    fun `reverse split converts whole groups and reports the leftover`() {
        val event = ReverseSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 10)
        val result = calculator.shareEntitlements(event, mapOf(a to Quantity.of(25))).single()
        assertEquals(Quantity.of(2), result.quantityAfter)
        assertEquals(Quantity.of("0.5"), result.residual)
    }

    @Test
    fun `reverse split of a holding smaller than one group yields zero shares and a fractional residual`() {
        val event = ReverseSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 7)
        val result = calculator.shareEntitlements(event, mapOf(a to Quantity.of(3))).single()
        assertEquals(Quantity.ZERO, result.quantityAfter)
        assertEquals(Quantity.of("0.428571"), result.residual)
    }

    // ---- ordering ----

    @Test
    fun `share entitlements come back sorted by account`() {
        val event = StockSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 2)
        val result = calculator.shareEntitlements(
            event,
            linkedMapOf(c to Quantity.of(1), a to Quantity.of(1), b to Quantity.of(1)),
        )
        assertEquals(listOf(a, b, c), result.map { it.account })
    }

    // ---- properties ----

    @Test
    fun `bonus never loses more than one micro-share per holder`() {
        val random = Random(11)
        repeat(2_000) {
            val before = Quantity.of(random.nextLong(0, 100_000))
            val rate = Rate.of(BigDecimal.valueOf(random.nextLong(1, 10_000_000_000L), 10))
            val result = single(bonus(rate.toString()), before)

            val exactBonus = before.value.multiply(rate.value)
            val credited = (result.quantityAfter - before).value.add(result.residual.value)
            val lost = exactBonus.subtract(credited)

            assertTrue(lost.signum() >= 0, "credited more than the exact bonus")
            assertTrue(lost < BigDecimal("0.000001"), "lost $lost")
            assertEquals(0, (result.quantityAfter - before).value.remainder(BigDecimal.ONE).signum())
            assertTrue(result.residual.value < BigDecimal.ONE)
        }
    }

    @Test
    fun `reverse split conserves old shares up to truncation of the residual`() {
        val random = Random(23)
        repeat(2_000) {
            val groupSize = random.nextInt(2, 50)
            val before = Quantity.of(random.nextLong(0, 100_000))
            val event = ReverseSplit(UUID.randomUUID(), isin, recordDate, paymentDate, groupSize)
            val result = calculator.shareEntitlements(event, mapOf(a to before)).single()

            val group = BigDecimal(groupSize)
            val leftoverOld = before.value.subtract(result.quantityAfter.value.multiply(group))
            val residualOld = result.residual.value.multiply(group)

            assertTrue(leftoverOld.signum() >= 0 && leftoverOld < group, "leftover $leftoverOld")
            assertTrue(residualOld <= leftoverOld, "residual exceeds leftover")
            assertTrue(leftoverOld.subtract(residualOld) < BigDecimal("0.000001").multiply(group))
        }
    }
}
