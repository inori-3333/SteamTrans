package com.steamtrans.ledger.data.market

import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.PlatformProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamMarketGatewayTest {
    @Test
    fun marketSearchCanOmitGameFilter() {
        val allGames = CommunityMarketGateway.buildSearchUrl("Booster Pack", null)
        val cs2Only = CommunityMarketGateway.buildSearchUrl("Case", 730)

        assertEquals("Booster Pack", allGames.queryParameter("query"))
        assertNull(allGames.queryParameter("appid"))
        assertEquals("730", cs2Only.queryParameter("appid"))
    }

    @Test
    fun parsesChineseCnyPriceWithoutFabricatingMissingValues() {
        assertEquals(123456L, CommunityMarketGateway.parseCnyPrice("¥ 1,234.56"))
        assertEquals(980L, CommunityMarketGateway.parseCnyPrice("CNY 9.8"))
        assertNull(CommunityMarketGateway.parseCnyPrice("暂无价格"))
        assertNull(CommunityMarketGateway.parseCnyPrice(""))
    }

    @Test
    fun estimatedSteamNetUsesConfiguredRateAndFixedFee() {
        val profile = PlatformProfileEntity(
            name = "Steam",
            defaultAccountType = AccountType.STEAM_WALLET_CNY,
            sellFeeRateBps = 1_500,
            sellFixedFeeCents = 2
        )
        assertEquals(848L, estimateNetPrice(1_000, profile))
        assertEquals(0L, estimateNetPrice(1, profile))
    }
}
