package com.x8bit.bitwarden.wear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.ui.accounts.WatchAccountsScreen
import com.x8bit.bitwarden.wear.ui.security.WatchSecurityGate
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point for the Wear OS app.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            BitwardenTheme(theme = AppTheme.DARK) {
                WatchSecurityGate(onOpenSettings = ::openSecuritySettings) {
                    WatchAccountsScreen(onOpenSettings = ::openSecuritySettings)
                }
            }
        }
    }

    private fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
