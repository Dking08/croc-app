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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.crocworks.app.ui.history.HistoryScreen
import com.crocworks.app.ui.navigation.CrocDestination
import com.crocworks.app.ui.receive.ReceiveScreen
import com.crocworks.app.ui.receive.ReceiveViewModel
import com.crocworks.app.ui.scanner.QrScannerScreen
import com.crocworks.app.ui.send.SendScreen
import com.crocworks.app.ui.send.SendViewModel
import com.crocworks.app.ui.settings.SettingsScreen
import com.crocworks.app.ui.theme.CrocTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle share intent
        val sharedUris = handleShareIntent(intent)

        setContent {
            CrocTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CrocApp(sharedUris = sharedUris)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle new share intents while app is running
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
fun CrocApp(sharedUris: List<Uri> = emptyList()) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Track if bottom bar should be visible
    val showBottomBar = currentRoute in CrocDestination.all.map { it.route }

    // Shared ViewModels for cross-screen communication
    val sendViewModel: SendViewModel = viewModel()
    val receiveViewModel: ReceiveViewModel = viewModel()

    // Handle shared files on launch
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    CrocDestination.all.forEach { destination ->
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
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = if (sharedUris.isNotEmpty()) CrocDestination.Send.route else CrocDestination.Send.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(CrocDestination.Send.route) {
                SendScreen(viewModel = sendViewModel)
            }
            composable(CrocDestination.Receive.route) {
                ReceiveScreen(
                    viewModel = receiveViewModel,
                    onOpenScanner = {
                        navController.navigate("scanner")
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
                    }
                )
            }
            composable(CrocDestination.Settings.route) {
                SettingsScreen()
            }
            composable("scanner") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        receiveViewModel.setCodeFromQr(code)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
