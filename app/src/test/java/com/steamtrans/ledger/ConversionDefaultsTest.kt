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

    @Test
    fun `booster pack quantity multiplies every supported gem cost`() {
        BOOSTER_GEM_COSTS.forEach { cost ->
            assertEquals((cost * 3).toString(), gemQuantityForBoosterPacks(cost, "3"))
        }
    }

    @Test
    fun `booster gem total rejects unsupported invalid and overflowing input`() {
        assertEquals("", gemQuantityForBoosterPacks(401, "2"))
        assertEquals("", gemQuantityForBoosterPacks(400, ""))
        assertEquals("", gemQuantityForBoosterPacks(400, "0"))
        assertEquals("", gemQuantityForBoosterPacks(1_200, Long.MAX_VALUE.toString()))
    }

    @Test
    fun `supported booster gem cost is restored from existing quantities`() {
        assertEquals(429L, boosterGemCostFor("1287", "3"))
        assertEquals(null, boosterGemCostFor("1201", "1"))
        assertEquals(null, boosterGemCostFor("1000", "3"))
    }
}
