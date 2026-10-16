package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.model.shared.Quantity
import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuantityTest {

    @Test
    fun `equality ignores trailing zeros`() {
        assertEquals(Quantity.of("1.5"), Quantity.of("1.500000"))
        assertEquals(Quantity.of(2), Quantity.of("2.0"))
    }

    @Test
    fun `rejects negative values`() {
        assertFailsWith<InvalidValueException> { Quantity.of(-1) }
        assertFailsWith<InvalidValueException> { Quantity.of("-0.5") }
    }

    @Test
    fun `rejects more precision than the supported scale`() {
        assertFailsWith<InvalidValueException> { Quantity.of("0.0000001") }
    }

    @Test
    fun `rejects non-numeric text`() {
        assertFailsWith<InvalidValueException> { Quantity.of("abc") }
    }

    @Test
    fun `adds and subtracts exactly`() {
        assertEquals(Quantity.of("3.3"), Quantity.of("1.1") + Quantity.of("2.2"))
        assertEquals(Quantity.of("1.1"), Quantity.of("3.3") - Quantity.of("2.2"))
    }

    @Test
    fun `subtraction cannot go below zero`() {
        assertFailsWith<InvalidValueException> { Quantity.of(1) - Quantity.of(2) }
    }

    @Test
    fun `signed difference works in both directions`() {
        assertEquals(BigDecimal("1.000000"), Quantity.of(3).signedDifference(Quantity.of(2)))
        assertEquals(BigDecimal("-1.000000"), Quantity.of(2).signedDifference(Quantity.of(3)))
    }

    @Test
    fun `zero is recognised`() {
        assertTrue(Quantity.ZERO.isZero())
        assertTrue(Quantity.of(0).isZero())
    }
}
