package tech.b3custodyreconciliation.domain.service

import tech.b3custodyreconciliation.domain.service.LargestRemainderDistributor
import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.shared.InvestorAccount
import tech.b3custodyreconciliation.domain.model.shared.Money
import tech.b3custodyreconciliation.domain.model.shared.Quantity
import tech.b3custodyreconciliation.domain.model.shared.total
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LargestRemainderDistributorTest {

    private val distributor = LargestRemainderDistributor()

    private val a = InvestorAccount("A")
    private val b = InvestorAccount("B")
    private val c = InvestorAccount("C")

    @Test
    fun `splits an indivisible total and gives the extra cent to the first account on ties`() {
        val result = distributor.distribute(
            Money(100),
            mapOf(a to Quantity.of(1), b to Quantity.of(1), c to Quantity.of(1)),
        )
        assertEquals(Money(34), result[a])
        assertEquals(Money(33), result[b])
        assertEquals(Money(33), result[c])
    }

    @Test
    fun `gives the leftover cent to the largest remainder, not to the first account`() {
        val result = distributor.distribute(Money(100), mapOf(a to Quantity.of(1), b to Quantity.of(2)))
        assertEquals(Money(33), result[a])
        assertEquals(Money(67), result[b])
    }

    @Test
    fun `handles fractional quantities`() {
        val result = distributor.distribute(Money(100), mapOf(a to Quantity.of("0.5"), b to Quantity.of("1.5")))
        assertEquals(Money(25), result[a])
        assertEquals(Money(75), result[b])
    }

    @Test
    fun `accounts with zero quantity receive nothing`() {
        val result = distributor.distribute(Money(10), mapOf(a to Quantity.of(5), b to Quantity.ZERO))
        assertEquals(Money(10), result[a])
        assertEquals(Money.ZERO, result[b])
    }

    @Test
    fun `a zero total yields zero for everyone`() {
        val result = distributor.distribute(Money.ZERO, mapOf(a to Quantity.of(1), b to Quantity.of(2)))
        assertTrue(result.values.all { it == Money.ZERO })
    }

    @Test
    fun `result does not depend on input order`() {
        val positions = (0 until 20).associate { InvestorAccount("ACC-%03d".format(it)) to Quantity.of((it % 7 + 1).toLong()) }
        val shuffled = positions.entries.shuffled(Random(7)).associate { it.key to it.value }
        assertEquals(
            distributor.distribute(Money(1_000_001), positions),
            distributor.distribute(Money(1_000_001), shuffled),
        )
    }

    @Test
    fun `rejects empty and all-zero inputs`() {
        assertFailsWith<InvalidValueException> { distributor.distribute(Money(100), emptyMap()) }
        assertFailsWith<InvalidValueException> { distributor.distribute(Money(100), mapOf(a to Quantity.ZERO)) }
    }

    @Test
    fun `conserves the total and stays within one cent of the exact share`() {
        val random = Random(42)
        repeat(2_000) {
            val size = random.nextInt(1, 51)
            val positions = (0 until size).associate {
                InvestorAccount("ACC-%03d".format(it)) to Quantity.of(random.nextLong(0, 10_001))
            }
            if (positions.values.all { it.isZero() }) return@repeat

            val total = Money(random.nextLong(0, 10_000_001))
            val result = distributor.distribute(total, positions)

            assertEquals(total, result.values.total())

            val totalUnits = positions.values.fold(BigInteger.ZERO) { acc, q -> acc + q.units }
            positions.forEach { (account, quantity) ->
                val floor = (BigInteger.valueOf(total.cents) * quantity.units / totalUnits).toLong()
                val actual = result.getValue(account).cents
                assertTrue(actual == floor || actual == floor + 1, "share of $account was $actual, exact floor $floor")
            }
        }
    }
}
