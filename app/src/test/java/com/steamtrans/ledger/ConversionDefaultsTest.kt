package com.steamtrans.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionDefaultsTest {
    @Test
    fun `gem bag quantity expands at one to one thousand`() {
        assertEquals("1000", gemQuantityForBags("1"))
        assertEquals("5000", gemQuantityForBags("5"))
    }

    @Test
    fun `blank invalid and overflowing quantities do not produce a default`() {
        assertEquals("", gemQuantityForBags(""))
        assertEquals("", gemQuantityForBags("not-a-number"))
        assertEquals("", gemQuantityForBags(Long.MAX_VALUE.toString()))
    }
}
