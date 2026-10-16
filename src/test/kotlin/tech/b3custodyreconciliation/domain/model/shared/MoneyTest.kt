package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {

    @Test
    fun `converts reais to cents`() {
        assertEquals(1234L, Money.ofReais(BigDecimal("12.34")).cents)
        assertEquals(1200L, Money.ofReais(BigDecimal("12")).cents)
    }

    @Test
    fun `rejects sub-cent precision instead of rounding it`() {
        assertFailsWith<InvalidValueException> { Money.ofReais(BigDecimal("0.001")) }
    }

    @Test
    fun `rejects negative amounts`() {
        assertFailsWith<InvalidValueException> { Money(-1) }
        assertFailsWith<InvalidValueException> { Money.ofReais(BigDecimal("-0.01")) }
    }

    @Test
    fun `formats with two decimal places`() {
        assertEquals("BRL 12.34", Money(1234).toString())
        assertEquals("BRL 0.05", Money(5).toString())
    }

    @Test
    fun `adds and subtracts`() {
        assertEquals(Money(300), Money(100) + Money(200))
        assertEquals(Money(100), Money(300) - Money(200))
    }

    @Test
    fun `subtraction cannot go below zero`() {
        assertFailsWith<InvalidValueException> { Money(1) - Money(2) }
    }

    @Test
    fun `totals an iterable`() {
        assertEquals(Money(6), listOf(Money(1), Money(2), Money(3)).total())
        assertEquals(Money.ZERO, emptyList<Money>().total())
    }
}
