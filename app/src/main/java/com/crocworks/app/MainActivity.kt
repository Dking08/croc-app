package com.crocworks.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crocworks.app.data.preferences.UserPreferencesRepository
import com.crocworks.app.ui.history.HistoryScreen
import com.crocworks.app.ui.navigation.CrocDestination
import com.crocworks.app.ui.receive.ReceiveScreen
import com.crocworks.app.ui.receive.ReceiveViewModel
import com.crocworks.app.ui.scanner.QrScannerScreen
import com.crocworks.app.ui.send.SendScreen
import com.crocworks.app.ui.send.SendViewModel
import com.crocworks.app.ui.settings.SettingsScreen
import com.crocworks.app.ui.settings.SettingsViewModel
import com.crocworks.app.ui.theme.CrocTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedUris = handleShareIntent(intent)

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()

            val isDark = when (prefs.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            CrocTheme(
                darkTheme = isDark,
                amoledDark = prefs.amoledDark
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CrocApp(sharedUris = sharedUris, settingsViewModel = settingsViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) listOf(uri) else emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }
    }
}

@Composable
fun CrocApp(
    sharedUris: List<Uri> = emptyList(),
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in CrocDestination.bottomNavItems.map { it.route }

    val sendViewModel: SendViewModel = viewModel()
    val receiveViewModel: ReceiveViewModel = viewModel()

    var handledSharedUris by rememberSaveable { mutableStateOf(false) }
    if (sharedUris.isNotEmpty() && !handledSharedUris) {
        handledSharedUris = true
        sendViewModel.addFiles(sharedUris)
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    CrocDestination.bottomNavItems.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label
                                )
                            },
                            label = {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = CrocDestination.Send.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(CrocDestination.Send.route) {
                SendScreen(
                    viewModel = sendViewModel,
                    onNavigateToHistory = {
                        navController.navigate(CrocDestination.History.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    }
                )
            }
            composable(CrocDestination.Receive.route) {
                ReceiveScreen(
                    viewModel = receiveViewModel,
                    onOpenScanner = {
                        navController.navigate("scanner")
                    },
                    onNavigateToHistory = {
                        navController.navigate(CrocDestination.History.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    }
                )
            }
            composable(CrocDestination.History.route) {
                HistoryScreen(
                    onCodeSelected = { code ->
                        receiveViewModel.setCodeFromQr(code)
                        navController.navigate(CrocDestination.Receive.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(CrocDestination.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("scanner") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        receiveViewModel.setCodeFromQr(code)
                        receiveViewModel.startReceive()
                        // Navigate back to Receive tab (not just popBackStack which could land on Send)
                        navController.navigate(CrocDestination.Receive.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
