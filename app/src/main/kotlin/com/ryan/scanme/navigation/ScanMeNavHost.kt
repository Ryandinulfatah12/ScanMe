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
import com.ryan.scanme.feature.documents.HomeScreen
import com.ryan.scanme.feature.scanner.ScannerScreen

private object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner?import={import}"

    fun scanner(import: Boolean) = "scanner?import=$import"
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
                onScan = { nav.navigate(Routes.scanner(import = false)) },
                onImport = { nav.navigate(Routes.scanner(import = true)) },
            )
        }
        composable(
            Routes.SCANNER,
            arguments = listOf(navArgument("import") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            ScannerScreen(
                startWithImport = entry.arguments?.getBoolean("import") == true,
                onClose = { nav.popBackStack() },
            )
        }
    }
}
