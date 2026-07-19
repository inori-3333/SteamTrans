package com.steamtrans.ledger

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.Holding
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.ui.editor.EditorScreen
import com.steamtrans.ledger.ui.theme.SteamLedgerTheme
import org.junit.Test

class EditorRestorationTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun editorLinesSurviveSavedInstanceStateRestore() = runComposeUiTest {
        val restorationTester = StateRestorationTester(this)
        val item = ItemEntity(id = 1, name = "测试胶囊", type = ItemType.CAPSULE)
        restorationTester.setContent {
            SteamLedgerTheme {
                EditorScreen(
                    eventId = null,
                    initialType = EventType.BUY,
                    allItems = listOf(item),
                    holdings = emptyList(),
                    events = emptyList(),
                    profiles = emptyList(),
                    recipes = emptyList(),
                    saving = false,
                    onClose = {},
                    onSave = {},
                    onUpdate = { _, _ -> },
                    onCreateAndBuy = { _, _ -> },
                    onCreateConversionOutputs = { _, _ -> },
                    onSaveRecipe = {}
                )
            }
        }

        onNodeWithText("数量").performTextReplacement("7")
        restorationTester.emulateSaveAndRestore()
        onNode(hasText("7")).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun boosterDefaultsToThreeNormalCardSlotsAndMergesByQuantity() = runComposeUiTest {
        val booster = ItemEntity(id = 1, name = "测试卡包", game = "测试游戏", type = ItemType.BOOSTER)
        setContent {
            SteamLedgerTheme {
                EditorScreen(
                    eventId = null,
                    initialType = EventType.CONVERT,
                    allItems = listOf(booster),
                    holdings = listOf(Holding(booster, quantity = 1, cost = com.steamtrans.ledger.data.CostVector(walletCents = 300))),
                    events = emptyList(),
                    profiles = emptyList(),
                    recipes = emptyList(),
                    saving = false,
                    onClose = {},
                    onSave = {},
                    onUpdate = { _, _ -> },
                    onCreateAndBuy = { _, _ -> },
                    onCreateConversionOutputs = { _, _ -> },
                    onSaveRecipe = {}
                )
            }
        }

        onAllNodesWithText("新卡牌定义").assertCountEquals(3)
        onAllNodesWithText("普通卡").assertCountEquals(3)

        onAllNodesWithText("数量")[1].performTextReplacement("2")
        onAllNodesWithText("新卡牌定义").assertCountEquals(2)

        onAllNodesWithText("数量")[1].performTextReplacement("3")
        onAllNodesWithText("新卡牌定义").assertCountEquals(1)
    }
}
