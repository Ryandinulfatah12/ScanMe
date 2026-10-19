package com.ryan.scanme.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ryan.scanme.core.ui.component.PlaceholderScreen

@Composable
fun EditorScreen(
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "Editor",
        description = "Crop, rotate and filters (Phase 3 step 5).",
        actions = actions,
        modifier = modifier,
    )
}
