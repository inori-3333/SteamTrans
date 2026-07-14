package com.steamtrans.ledger.ui.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendChartTest {
    @Test
    fun tapSelectsNearestTrendPointAndClampsToChartEdges() {
        assertEquals(0, trendPointIndexForX(-20f, 100f, 5))
        assertEquals(2, trendPointIndexForX(50f, 100f, 5))
        assertEquals(4, trendPointIndexForX(120f, 100f, 5))
    }

    @Test
    fun invalidChartHasNoSelectablePoint() {
        assertNull(trendPointIndexForX(10f, 100f, 0))
        assertNull(trendPointIndexForX(10f, 0f, 3))
        assertNull(trendPointIndexForX(Float.NaN, 100f, 3))
    }
}
