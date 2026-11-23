package com.ryan.scanme.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ryan.scanme.feature.debug.PipelineDebugScreen

/** Debug builds only (`:feature:debug` is a debugImplementation); the release twin is empty. */
internal val DEBUG_ROUTE: String? = "debug"

internal fun NavGraphBuilder.debugDestinations(nav: NavController) {
    composable("debug") { PipelineDebugScreen(onBack = { nav.popBackStack() }) }
}
