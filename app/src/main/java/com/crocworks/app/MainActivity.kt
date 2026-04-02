package com.crocworks.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.text.font.FontWeight
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
import com.crocworks.app.ui.quick.QuickScreen
import com.crocworks.app.ui.quick.QuickViewModel
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
    val quickViewModel: QuickViewModel = viewModel()

    var handledSharedUris by rememberSaveable { mutableStateOf(false) }
    if (sharedUris.isNotEmpty() && !handledSharedUris) {
        handledSharedUris = true
        sendViewModel.addFiles(sharedUris)
    }

    // Determine tab indices for directional animations
    val tabRoutes = CrocDestination.bottomNavItems.map { it.route }

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
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
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
            startDestination = CrocDestination.Quick.route,
            modifier = Modifier.padding(paddingValues),
            enterTransition = {
                // Determine direction based on tab index
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                when {
                    fromIndex >= 0 && toIndex >= 0 -> {
                        // Tab-to-tab: slide horizontally with spring
                        val direction = if (toIndex > fromIndex) 1 else -1
                        slideInHorizontally(
                            initialOffsetX = { direction * it / 4 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeIn(
                            animationSpec = tween(220)
                        )
                    }
                    else -> {
                        // Push screens: slide up
                        fadeIn(animationSpec = tween(300)) +
                                slideInVertically(
                                    initialOffsetY = { it / 6 },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                    }
                }
            },
            exitTransition = {
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                when {
                    fromIndex >= 0 && toIndex >= 0 -> {
                        val direction = if (toIndex > fromIndex) -1 else 1
                        slideOutHorizontally(
                            targetOffsetX = { direction * it / 4 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) + fadeOut(
                            animationSpec = tween(220)
                        )
                    }
                    else -> {
                        fadeOut(animationSpec = tween(200))
                    }
                }
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(300)) +
                        slideInVertically(
                            initialOffsetY = { -it / 8 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(200)) +
                        slideOutVertically(
                            targetOffsetY = { it / 6 },
                            animationSpec = tween(250)
                        )
            }
        ) {
            composable(CrocDestination.Quick.route) {
                QuickScreen(
                    viewModel = quickViewModel,
                    onOpenScanner = { onCodeScanned ->
                        // Store callback and navigate to scanner
                        navController.navigate("scanner_quick")
                    },
                    onNavigateToSettings = {
                        navController.navigate(CrocDestination.Settings.route)
                    }
                )
            }
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
            composable("scanner_quick") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        quickViewModel.startReceiveFromQr(code)
                        navController.navigate(CrocDestination.Quick.route) {
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
