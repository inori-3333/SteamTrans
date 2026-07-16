package com.steamtrans.ledger.data.market

import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.PlatformProfileEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamMarketGatewayTest {
    @Test
    fun parsesNoRenderSearchResultsInsteadOfLookingForHtml() {
        val response = Json.parseToJsonElement(
            """
            {
              "success": true,
              "total_count": 1,
              "results": [
                {
                  "name": "Dreams & Nightmares Case",
                  "hash_name": "Dreams & Nightmares Case",
                  "app_name": "Counter-Strike 2",
                  "asset_description": {
                    "appid": 730,
                    "icon_url": "sample-icon-hash",
                    "market_name": "Dreams & Nightmares Case",
                    "market_hash_name": "Dreams & Nightmares Case"
                  }
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val result = CommunityMarketGateway.parseSearchResults(response, null).single()

        assertEquals(730, result.appId)
        assertEquals("Dreams & Nightmares Case", result.marketHashName)
        assertEquals("Counter-Strike 2", result.gameName)
        assertEquals(
            "https://steamcommunity.com/market/listings/730/Dreams%20&%20Nightmares%20Case",
            result.listingUrl
        )
        assertEquals(
            "https://community.fastly.steamstatic.com/economy/image/sample-icon-hash/96fx96f",
            result.imageUrl
        )
    }

    @Test
    fun marketSearchCanOmitGameFilter() {
        val allGames = CommunityMarketGateway.buildSearchUrl("Booster Pack", null)
        val cs2Only = CommunityMarketGateway.buildSearchUrl("Case", 730)

        assertEquals("Booster Pack", allGames.queryParameter("query"))
        assertNull(allGames.queryParameter("appid"))
        assertEquals("730", cs2Only.queryParameter("appid"))
    }

    @Test
    fun parsesAndNormalizesSteamMarketListingUrl() {
        val listing = parseSteamMarketListingUrl(
            " https://www.steamcommunity.com/market/listings/730/Dreams%20%26%20Nightmares%20Case?l=schinese "
        )

        assertEquals(730, listing.appId)
        assertEquals("Dreams & Nightmares Case", listing.marketHashName)
        assertEquals(
            "https://steamcommunity.com/market/listings/730/Dreams%20&%20Nightmares%20Case",
            listing.listingUrl
        )
    }

    @Test
    fun rejectsUrlsThatAreNotSteamMarketListings() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSteamMarketListingUrl("https://example.com/market/listings/730/Item")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSteamMarketListingUrl("https://steamcommunity.com/market/search?q=Item")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSteamMarketListingUrl("http://steamcommunity.com/market/listings/730/Item")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSteamMarketListingUrl("https://steamcommunity.com/market/listings/not-an-app/Item")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSteamMarketListingUrl("https://steamcommunity.com:444/market/listings/730/Item")
        }
    }

    @Test
    fun parsesChineseCnyPriceWithoutFabricatingMissingValues() {
        assertEquals(123456L, CommunityMarketGateway.parseCnyPrice("¥ 1,234.56"))
        assertEquals(980L, CommunityMarketGateway.parseCnyPrice("CNY 9.8"))
        assertNull(CommunityMarketGateway.parseCnyPrice("暂无价格"))
        assertNull(CommunityMarketGateway.parseCnyPrice(""))
    }

    @Test
    fun usesHighestBuyOrderWhenLowestListingPriceIsMissing() {
        val overview = Json.parseToJsonElement(
            """{"success":true,"volume":"3"}"""
        ).jsonObject
        val histogram = Json.parseToJsonElement(
            """{"success":1,"highest_buy_order":"1234","buy_order_price":"¥ 12.34"}"""
        ).jsonObject

        assertNull(CommunityMarketGateway.parseLowestListingPrice(overview))
        assertEquals(1234L, CommunityMarketGateway.parseHighestBuyOrderPrice(histogram))
    }

    @Test
    fun extractsItemNameIdNeededForBuyOrderFallback() {
        val html = """
            <script>
                Market_LoadOrderSpread( 176024744 );
            </script>
        """.trimIndent()

        assertEquals("176024744", CommunityMarketGateway.parseItemNameId(html))
        assertNull(CommunityMarketGateway.parseItemNameId("<html></html>"))
    }

    @Test
    fun extractsItemNameIdFromActivityTickerFallback() {
        val html = """
            <script>
                ItemActivityTicker.Start( 176024744 );
            </script>
        """.trimIndent()

        assertEquals("176024744", CommunityMarketGateway.parseItemNameId(html))
    }

    @Test
    fun recognizesSteamRateLimitPagesInsteadOfTreatingThemAsItemPages() {
        val english = """
            <h2>Sorry!</h2>
            You've made too many requests recently. Please wait and try your request again later.
        """.trimIndent()

        assertEquals(true, CommunityMarketGateway.isRateLimitedResponse(english))
        assertEquals(true, CommunityMarketGateway.isRateLimitedResponse("您最近的请求次数过多，请稍后重试。"))
        assertEquals(false, CommunityMarketGateway.isRateLimitedResponse("Market_LoadOrderSpread( 176024744 );"))
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
