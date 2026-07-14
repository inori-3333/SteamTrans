package com.steamtrans.ledger.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.steamtrans.ledger.LedgerViewModel
import com.steamtrans.ledger.data.AccountType
import com.steamtrans.ledger.data.EventType
import com.steamtrans.ledger.data.TrackingMode
import com.steamtrans.ledger.ui.editor.EditorScreen
import com.steamtrans.ledger.ui.holdings.HoldingsScreen
import com.steamtrans.ledger.ui.migration.MigrationSetupDialog
import com.steamtrans.ledger.ui.overview.OverviewScreen
import com.steamtrans.ledger.ui.settings.SettingsScreen
import com.steamtrans.ledger.ui.theme.PageBlue
import com.steamtrans.ledger.ui.transactions.LedgerScreen

private data class MainDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val mainDestinations = listOf(
    MainDestination("overview", "总览", Icons.Outlined.SpaceDashboard),
    MainDestination("holdings", "持仓", Icons.Outlined.Inventory2),
    MainDestination("ledger", "流水", Icons.Outlined.ReceiptLong)
)

@Composable
fun LedgerApp(vm: LedgerViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val profiles by vm.platformProfiles.collectAsStateWithLifecycle()
    val portfolioSnapshots by vm.portfolioSnapshots.collectAsStateWithLifecycle()
    val marketQuotes by vm.marketQuotes.collectAsStateWithLifecycle()
    val operationError by vm.operationError.collectAsStateWithLifecycle()
    val operationMessage by vm.operationMessage.collectAsStateWithLifecycle()
    val saving by vm.operationInProgress.collectAsStateWithLifecycle()
    val refreshState by vm.marketRefresh.collectAsStateWithLifecycle()
    val searchState by vm.marketSearch.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val migrationCompleted by vm.migrationCompleted.collectAsStateWithLifecycle()
    val conversionRecipes by vm.conversionRecipes.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route.orEmpty()
    val isMain = route in mainDestinations.map { it.route }
    val snackbarHost = remember { SnackbarHostState() }
    var ledgerAccountFilter by remember { mutableStateOf<AccountType?>(null) }

    LaunchedEffect(operationError) {
        operationError?.let {
            snackbarHost.showSnackbar(it)
            vm.clearOperationError()
        }
    }
    LaunchedEffect(operationMessage) {
        operationMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.clearOperationMessage()
        }
    }
    LaunchedEffect(snapshot.error) { snapshot.error?.let { snackbarHost.showSnackbar(it) } }

    Scaffold(
        containerColor = PageBlue,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (isMain) {
                NavigationBar {
                    mainDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = route == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo("overview") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isMain) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate("editor/-1/${EventType.BUY.name}") },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("记一笔") }
                )
            }
        }
    ) { outerPadding ->
        NavHost(navController, startDestination = "overview", modifier = Modifier.then(if (isMain) Modifier else Modifier)) {
            composable("overview") {
                androidx.compose.foundation.layout.Box(Modifier.padding(outerPadding)) {
                    OverviewScreen(
                        snapshot = snapshot,
                        portfolioSnapshots = portfolioSnapshots,
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenLedger = { account -> ledgerAccountFilter = account; navController.navigate("ledger") },
                        onAdjustAccount = vm::adjustAccount,
                        onRemovePortfolioSnapshot = vm::removePortfolioSnapshot
                    )
                }
            }
            composable("holdings") {
                androidx.compose.foundation.layout.Box(Modifier.padding(outerPadding)) {
                    HoldingsScreen(
                        allItems = items,
                        snapshot = snapshot,
                        marketQuotes = marketQuotes,
                        refreshState = refreshState,
                        searchState = searchState,
                        onRefresh = vm::refreshCurrentHoldings,
                        onSearchMarket = vm::searchMarket,
                        onClearSearch = vm::clearMarketSearch,
                        onBindMarket = { itemId, result -> vm.bindMarket(itemId, result) },
                        onUnbindMarket = vm::unbindMarket,
                        onManualQuote = { itemId, cents -> vm.setManualQuote(itemId, cents) }
                    )
                }
            }
            composable("ledger") {
                androidx.compose.foundation.layout.Box(Modifier.padding(outerPadding)) {
                    LedgerScreen(
                        snapshot = snapshot,
                        itemsById = items.associateBy { it.id },
                        initialAccount = ledgerAccountFilter,
                        onEdit = { id -> navController.navigate("editor/$id/${EventType.BUY.name}") },
                        onSetVoided = vm::setEventVoided
                    )
                }
            }
            composable("settings") {
                SettingsScreen(
                    snapshot = snapshot,
                    itemCount = items.size,
                    quoteCount = snapshot.holdings.count { it.quote != null },
                    profiles = profiles,
                    pendingRestore = pendingRestore,
                    onBack = { navController.popBackStack() },
                    onUpdateProfile = vm::updatePlatformProfile,
                    onExportBackup = vm::exportBackup,
                    onExportCsv = vm::exportCsv,
                    onInspectBackup = vm::inspectBackup,
                    onCancelRestore = vm::cancelRestore,
                    onConfirmRestore = vm::confirmRestore,
                    onClearMarket = vm::clearMarketData,
                    onClearAll = vm::clearAll
                )
            }
            composable(
                route = "editor/{eventId}/{type}",
                arguments = listOf(
                    navArgument("eventId") { type = NavType.LongType },
                    navArgument("type") { type = NavType.StringType }
                )
            ) { entry ->
                val id = entry.arguments?.getLong("eventId")?.takeIf { it >= 0 }
                val initialType = entry.arguments?.getString("type")?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
                EditorScreen(
                    eventId = id,
                    initialType = initialType,
                    allItems = items,
                    holdings = snapshot.holdings,
                    events = snapshot.events,
                    profiles = profiles,
                    recipes = conversionRecipes,
                    saving = saving,
                    onClose = { navController.popBackStack() },
                    onSave = { draft -> vm.addEvent(draft) { navController.popBackStack() } },
                    onUpdate = { eventId, draft -> vm.updateEvent(eventId, draft) { navController.popBackStack() } },
                    onCreateAndBuy = { item, draft -> vm.addItemWithInitialBuy(item, draft) { navController.popBackStack() } },
                    onCreateConversionOutput = { item, draft -> vm.addItemWithConversionOutput(item, draft) { navController.popBackStack() } },
                    onSaveRecipe = vm::saveConversionRecipe
                )
            }
        }
    }

    val unreviewed = items.firstOrNull { !it.trackingReviewed }
    if (unreviewed != null) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.AccountBalanceWallet, null) },
            title = { Text("确认旧持仓方式") },
            text = {
                Text(
                    "“${unreviewed.name}”来自旧版 SKIN 分类。它是需要逐件追踪的饰品，还是按数量汇总的武器箱、胶囊、纪念包等商品？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = { TextButton(onClick = { vm.reviewTracking(unreviewed.id, TrackingMode.INDIVIDUAL) }) { Text("逐件饰品") } },
            dismissButton = { TextButton(onClick = { vm.reviewTracking(unreviewed.id, TrackingMode.STACKABLE) }) { Text("按量商品") } }
        )
    } else if (!migrationCompleted && snapshot.events.any { it.event.legacy }) {
        val known = setOf("steam", "buff", "uuyp", "淘宝")
        val unknownPlatforms = snapshot.events.asSequence()
            .filter { it.event.legacy }
            .map { it.event.platform.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in known }
            .distinct()
            .sorted()
            .toList()
        MigrationSetupDialog(
            currentWalletBalance = snapshot.walletBalanceCents,
            currentFiatBalance = snapshot.fiatBalanceCents,
            unknownPlatforms = unknownPlatforms,
            onComplete = { mappings, wallet, fiat -> vm.completeLegacyMigration(mappings, wallet, fiat) }
        )
    }
}
