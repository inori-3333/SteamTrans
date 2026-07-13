package com.steamtrans.ledger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.steamtrans.ledger.data.LedgerSnapshot
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
}
