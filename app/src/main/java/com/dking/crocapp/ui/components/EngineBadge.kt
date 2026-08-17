package com.dking.crocapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dking.crocapp.R
import com.dking.crocapp.croc.CrocEngine

@Composable
fun EngineBadge(
    engine: CrocEngine,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showCurrentMode: Boolean = false
) {
    val isLegacy = engine == CrocEngine.LEGACY
    if (!isLegacy && !showCurrentMode && onClick == null) {
        return
    }

    val containerColor = if (isLegacy) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isLegacy) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (isLegacy) Icons.Rounded.History else Icons.Rounded.RocketLaunch
    val text = if (isLegacy) stringResource(R.string.badge_legacy_mode) else stringResource(R.string.badge_current_mode)

    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = contentColor
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = stringResource(R.string.settings_try_legacy_first),
                    modifier = Modifier.size(12.dp),
                    tint = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}
