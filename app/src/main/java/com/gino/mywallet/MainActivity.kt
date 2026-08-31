package com.gino.mywallet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gino.mywallet.ui.AppViewModel
import com.gino.mywallet.ui.components.ScreenColumn
import com.gino.mywallet.ui.screens.EntertainmentScreen
import com.gino.mywallet.ui.screens.FocusScreen
import com.gino.mywallet.ui.screens.IncomeScreen
import com.gino.mywallet.ui.screens.MarketScreen
import com.gino.mywallet.ui.screens.PassFullScreen
import com.gino.mywallet.ui.screens.SettingsScreen
import com.gino.mywallet.ui.screens.TasksScreen
import com.gino.mywallet.ui.screens.WalletScreen
import com.gino.mywallet.ui.theme.MyWalletTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 使用者拒絕則僅不顯示通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        enableEdgeToEdge()
        setContent {
            MyWalletTheme {
                AppRoot(vm)
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("娛樂", Icons.Outlined.Casino),
    Tab("任務", Icons.Outlined.Checklist),
    Tab("錢包", Icons.Outlined.AccountBalanceWallet)
)

@Composable
private fun AppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message?.id) {
        message?.let { msg ->
            val duration = if (msg.text.length > 24) SnackbarDuration.Long else SnackbarDuration.Short
            snackbarHostState.showSnackbar(msg.text, duration = duration)
            vm.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "main",
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            composable("main") { MainPagerScaffold(vm, nav) }
            composable("income") {
                SubPage("記一筆收入", nav) {
                    IncomeScreen(vm, onDone = { nav.popBackStack() })
                }
            }
            composable("market") {
                SubPage("借貸市場", nav, scrollable = false) { MarketScreen(vm) }
            }
            composable("settings") {
                SubPage("設定", nav) { SettingsScreen(vm) }
            }
            composable("pass") {
                val season by vm.season.collectAsStateWithLifecycle()
                val tiers by vm.tiers.collectAsStateWithLifecycle()
                val claimFlash by vm.passClaimReveal.collectAsStateWithLifecycle()
                SubPage("本月通行證", nav) {
                    ScreenColumn {
                        PassFullScreen(
                            season = season,
                            tiers = tiers,
                            onClaim = { vm.claimPassTier(it) },
                            claimFlash = claimFlash,
                            onDismissClaimFlash = { vm.dismissPassClaimReveal() }
                        )
                    }
                }
            }
            composable("focus") {
                FocusScreen(vm = vm, onFinished = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun MainPagerScaffold(vm: AppViewModel, nav: NavHostController) {
    val pagerState = rememberPagerState(initialPage = 2) { TABS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> {
                    ScrollPage {
                        EntertainmentScreen(
                            vm = vm,
                            onOpenPass = { nav.navigate("pass") }
                        )
                    }
                }
                1 -> TasksScreen(
                    vm = vm,
                    onOpenFocus = { nav.navigate("focus") }
                )
                else -> ScrollPage {
                    WalletScreen(
                        vm = vm,
                        onOpenIncome = { nav.navigate("income") },
                        onOpenMarket = { nav.navigate("market") },
                        onOpenSettings = { nav.navigate("settings") }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(
    title: String,
    nav: NavHostController,
    scrollable: Boolean = true,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = floatingActionButton
    ) { padding ->
        if (scrollable) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ScrollPage(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        content()
    }
}
