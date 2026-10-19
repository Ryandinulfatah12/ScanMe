package com.ryan.scanme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.ryan.scanme.core.ui.theme.ScanMeColors
import com.ryan.scanme.core.ui.theme.ScanMeTheme
import com.ryan.scanme.navigation.ScanMeNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light system bars with dark icons to match the fixed light theme; screens pad for insets themselves.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(ScanMeColors.Background.toArgb(), ScanMeColors.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.light(ScanMeColors.Background.toArgb(), ScanMeColors.Background.toArgb()),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ScanMeTheme {
                ScanMeNavHost()
            }
        }
    }
}
