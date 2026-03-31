package com.crocworks.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.graphics.vector.ImageVector

sealed class CrocDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Send : CrocDestination(
        route = "send",
        label = "Send",
        selectedIcon = Icons.Filled.Upload,
        unselectedIcon = Icons.Outlined.Upload
    )

    data object Receive : CrocDestination(
        route = "receive",
        label = "Receive",
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download
    )

    data object History : CrocDestination(
        route = "history",
        label = "History",
        selectedIcon = Icons.Filled.Download, // not used in bottom nav
        unselectedIcon = Icons.Outlined.Download
    )

    data object Settings : CrocDestination(
        route = "settings",
        label = "Settings",
        selectedIcon = Icons.Filled.Download, // not used in bottom nav
        unselectedIcon = Icons.Outlined.Download
    )

    companion object {
        /** Only Send & Receive appear in the bottom navigation */
        val bottomNavItems = listOf(Send, Receive)
    }
}
