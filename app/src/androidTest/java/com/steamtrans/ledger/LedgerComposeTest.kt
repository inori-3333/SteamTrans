package com.steamtrans.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.steamtrans.ledger.data.CostVector
import com.steamtrans.ledger.data.Holding
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.data.LedgerSnapshot
import com.steamtrans.ledger.data.MarketQuoteEntity
import com.steamtrans.ledger.data.PriceSource
import com.steamtrans.ledger.ui.holdings.HoldingsScreen
import com.steamtrans.ledger.ui.theme.SteamLedgerTheme
import org.junit.Rule
import org.junit.Test

class LedgerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun holdingsExposesFullCategoryAndBindingFilters() {
        composeRule.setContent {
            SteamLedgerTheme {
                HoldingsScreen(
                    allItems = emptyList(),
                    snapshot = LedgerSnapshot(),
                    marketQuotes = emptyList(),
                    refreshState = MarketRefreshState(),
                    searchState = MarketSearchState(),
                    onRefresh = {},
                    onSearchMarket = { _, _ -> },
                    onClearSearch = {},
                    onBindMarket = { _, _ -> },
                    onUnbindMarket = {},
                    onManualQuote = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("类别").performScrollTo().performClick()
        composeRule.onNodeWithText("武器箱 / 容器").assertExists().performClick()
        composeRule.onNodeWithText("武器箱 / 容器").assertExists()
        composeRule.onNodeWithText("未绑定").assertExists().performScrollTo().performClick()
    }

    @Test
    fun holdingsRendersAnItemAfterMarketBindingAndQuoteUpdate() {
        val item = ItemEntity(
            id = 1,
            name = "Dreams & Nightmares Case",
            game = "CS2",
            type = ItemType.CASE_CONTAINER,
            tradeable = true,
            marketAppId = 730,
            marketHashName = "Dreams & Nightmares Case",
            imageUrl = "https://community.fastly.steamstatic.com/economy/image/sample-icon/96fx96f"
        )
        val quote = MarketQuoteEntity(
            itemId = item.id,
            grossPriceCents = 250,
            estimatedNetPriceCents = 212,
            source = PriceSource.STEAM_MARKET,
            timestamp = System.currentTimeMillis()
        )
        composeRule.setContent {
            SteamLedgerTheme {
                HoldingsScreen(
                    allItems = listOf(item),
                    snapshot = LedgerSnapshot(
                        holdings = listOf(Holding(item, quantity = 1, cost = CostVector(walletCents = 100), quote = quote)),
                        marketGrossValueCents = 250,
                        marketNetValueCents = 212
                    ),
                    marketQuotes = listOf(quote),
                    refreshState = MarketRefreshState(),
                    searchState = MarketSearchState(),
                    onRefresh = {},
                    onSearchMarket = { _, _ -> },
                    onClearSearch = {},
                    onBindMarket = { _, _ -> },
                    onUnbindMarket = {},
                    onManualQuote = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Dreams & Nightmares Case").assertExists().performClick()
        composeRule.onNodeWithText("单件挂牌").assertExists()
        composeRule.waitForIdle()
    }
}
