package com.ryan.scanme.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ryan.scanme.core.ui.theme.ScanMeColors

/** White rounded card with an outline icon, a bold title and a one-line muted subtitle (home grid). */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(ScanMeColors.Surface)
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ScanMeColors.Muted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = ScanMeColors.Ink)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = ScanMeColors.Ink.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Solid circular button (black "+" on home, accent "share" on review). */
@Composable
fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = ScanMeColors.Ink,
    content: Color = Color.White,
    size: Dp = 60.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(size * 0.42f))
    }
}

/** Icon above a small label, for bottom toolbars. */
@Composable
fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ScanMeColors.Accent,
    enabled: Boolean = true,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val color = if (enabled) tint else tint.copy(alpha = 0.35f)
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Short message pill, e.g. "PDF tersimpan". */
@Composable
fun MessagePill(text: String, modifier: Modifier = Modifier, dark: Boolean = true) {
    Text(
        text,
        color = if (dark) Color.White else ScanMeColors.Ink,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (dark) Color(0xE6111113) else ScanMeColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
