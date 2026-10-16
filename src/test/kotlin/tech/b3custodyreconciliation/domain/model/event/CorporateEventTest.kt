package tech.b3custodyreconciliation.domain.model.event

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import tech.b3custodyreconciliation.domain.model.shared.Isin
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorporateEventTest {

    private val isin = Isin("BRPETRACNPR6")
    private val recordDate = LocalDate.of(2026, 10, 20)
    private val paymentDate = LocalDate.of(2026, 11, 5)

    @Test
    fun `rate equality ignores trailing zeros`() {
        assertEquals(Rate.of("0.25"), Rate.of("0.2500000000"))
    }

    @Test
    fun `rate must be positive and within its precision`() {
        assertFailsWith<InvalidValueException> { Rate.of("0") }
        assertFailsWith<InvalidValueException> { Rate.of("-0.1") }
        assertFailsWith<InvalidValueException> { Rate.of("0.00000000001") }
        assertFailsWith<InvalidValueException> { Rate.of("abc") }
    }

    @Test
    fun `payment cannot precede the record date`() {
        assertFailsWith<InvalidValueException> {
            Dividend(UUID.randomUUID(), isin, recordDate, recordDate.minusDays(1), Rate.of("0.25"))
        }
        assertFailsWith<InvalidValueException> {
            InterestOnEquity(UUID.randomUUID(), isin, recordDate, recordDate.minusDays(1), Rate.of("0.25"))
        }
    }

    @Test
    fun `payment on the record date itself is allowed`() {
        Dividend(UUID.randomUUID(), isin, recordDate, recordDate, Rate.of("0.25"))
    }

    @Test
    fun `split factor and group size must be at least two`() {
        assertFailsWith<InvalidValueException> { StockSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 1) }
        assertFailsWith<InvalidValueException> { ReverseSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 1) }
        StockSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 2)
        ReverseSplit(UUID.randomUUID(), isin, recordDate, paymentDate, 2)
    }
}
