package com.steamtrans.ledger

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.steamtrans.ledger.data.EventType
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
                    onCreateConversionOutput = { _, _ -> },
                    onSaveRecipe = {}
                )
            }
        }

        onNodeWithText("数量").performTextReplacement("7")
        restorationTester.emulateSaveAndRestore()
        onNode(hasText("7")).assertExists()
    }
}
