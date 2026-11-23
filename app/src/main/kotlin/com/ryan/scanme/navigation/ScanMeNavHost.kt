package com.ryan.scanme.navigation

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ryan.scanme.core.ui.theme.ScanMeColors
import com.ryan.scanme.feature.documents.AboutScreen
import com.ryan.scanme.feature.documents.HomeScreen
import com.ryan.scanme.feature.scanner.ScannerScreen
import com.ryan.scanme.feature.scanner.ScannerViewModel

private object Routes {
    const val HOME = "home"
    const val ABOUT = "about"
    const val SCANNER = "scanner?import={import}&${ScannerViewModel.ARG_DOCUMENT}={${ScannerViewModel.ARG_DOCUMENT}}"

    fun scanner(import: Boolean = false, documentId: Long = -1) =
        "scanner?import=$import&${ScannerViewModel.ARG_DOCUMENT}=$documentId"
}

@Composable
fun ScanMeNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier.background(ScanMeColors.Background),
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.scanner()) },
                onImport = { nav.navigate(Routes.scanner(import = true)) },
                onOpenDocument = { id -> nav.navigate(Routes.scanner(documentId = id)) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onDebug = DEBUG_ROUTE?.let { route -> { nav.navigate(route) } },
            )
        }
        composable(
            Routes.SCANNER,
            arguments = listOf(
                navArgument("import") { type = NavType.BoolType; defaultValue = false },
                navArgument(ScannerViewModel.ARG_DOCUMENT) { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            ScannerScreen(
                startWithImport = entry.arguments?.getBoolean("import") == true,
                openingDocument = (entry.arguments?.getLong(ScannerViewModel.ARG_DOCUMENT) ?: -1L) >= 0,
                onClose = { nav.popBackStack() },
            )
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        debugDestinations(nav)
    }
}
