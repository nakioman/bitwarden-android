package com.bitwarden.authenticator.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bitwarden.authenticator.wear.ui.NoAccountsScreen

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
