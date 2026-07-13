package com.steamtrans.ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.steamtrans.ledger.ui.LedgerApp
import com.steamtrans.ledger.ui.theme.SteamLedgerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SteamLedgerTheme { LedgerApp() }
        }
    }
}
