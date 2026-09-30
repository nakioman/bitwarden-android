package com.x8bit.bitwarden.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.x8bit.bitwarden.wear.ui.NoAccountsScreen

/**
 * Entry point for the Wear OS app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            NoAccountsScreen()
        }
    }
}
