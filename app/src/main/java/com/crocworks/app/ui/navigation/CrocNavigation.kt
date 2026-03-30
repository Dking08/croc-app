package com.crocworks.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
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
        selectedIcon = Icons.Rounded.Send,
        unselectedIcon = Icons.Outlined.Send
    )

    data object Receive : CrocDestination(
        route = "receive",
        label = "Receive",
        selectedIcon = Icons.Rounded.Download,
        unselectedIcon = Icons.Outlined.Download
    )

    data object History : CrocDestination(
        route = "history",
        label = "History",
        selectedIcon = Icons.Rounded.History,
        unselectedIcon = Icons.Outlined.History
    )

    data object Settings : CrocDestination(
        route = "settings",
        label = "Settings",
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )

    companion object {
        val all = listOf(Send, Receive, History, Settings)
    }
}
