package com.ryan.scanme.feature.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ryan.scanme.core.ui.component.PlaceholderScreen

@Composable
fun PipelineDebugScreen(
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    PlaceholderScreen(
        title = "Pipeline debug",
        description = "Side-by-side output of every pipeline stage (Phase 3 step 3).",
        actions = actions,
        modifier = modifier,
    )
}
