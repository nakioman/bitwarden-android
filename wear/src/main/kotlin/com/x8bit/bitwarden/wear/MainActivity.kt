package com.x8bit.bitwarden.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.ui.NoAccountsScreen
import com.x8bit.bitwarden.wear.ui.VerificationCodeListScreen

/**
 * Entry point for the Wear OS app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            BitwardenTheme(theme = AppTheme.DARK) {
                if (BuildConfig.DEBUG) {
                    VerificationCodeListScreen()
                } else {
                    NoAccountsScreen()
                }
            }
        }
    }
}
