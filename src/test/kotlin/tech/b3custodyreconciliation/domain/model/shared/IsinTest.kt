package tech.b3custodyreconciliation.domain.model.shared

import tech.b3custodyreconciliation.domain.exception.InvalidValueException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IsinTest {

    @Test
    fun `accepts well-formed ISINs with a valid check digit`() {
        listOf("BRPETRACNPR6", "BRVALEACNOR0", "BRITUBACNPR1", "US0378331005").forEach {
            assertEquals(it, Isin(it).value)
        }
    }

    @Test
    fun `rejects a wrong check digit`() {
        assertFailsWith<InvalidValueException> { Isin("BRPETRACNPR7") }
        assertFailsWith<InvalidValueException> { Isin("US0378331006") }
    }

    @Test
    fun `rejects malformed codes`() {
        assertFailsWith<InvalidValueException> { Isin("BR123") }
        assertFailsWith<InvalidValueException> { Isin("") }
        assertFailsWith<InvalidValueException> { Isin("1RPETRACNPR6") }
        assertFailsWith<InvalidValueException> { Isin("brpetracnpr6") }
    }

    @Test
    fun `parse trims and upper-cases external input`() {
        assertEquals(Isin("BRPETRACNPR6"), Isin.parse("  brpetracnpr6 "))
    }

    @Test
    fun `exposes the country code`() {
        assertEquals("BR", Isin("BRPETRACNPR6").countryCode)
    }
}
