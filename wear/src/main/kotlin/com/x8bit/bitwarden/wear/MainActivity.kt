package com.x8bit.bitwarden.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.SwipeToDismissBox
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.ui.NoAccountsScreen
import com.x8bit.bitwarden.wear.ui.VerificationCodeDetailPager
import com.x8bit.bitwarden.wear.ui.VerificationCodeListScreen
import com.x8bit.bitwarden.wear.ui.previewAccounts
import java.time.Clock

private const val DETAIL_ENTER_DURATION_MILLIS = 220
private const val DETAIL_ENTER_INITIAL_SCALE = 0.82f

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
                    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
                    val favorites = remember { mutableStateMapOf<Int, Boolean>() }
                    val listState = rememberLazyListState()
                    val clock = remember { Clock.systemDefaultZone() }
                    BackHandler(enabled = selectedIndex >= 0) { selectedIndex = -1 }
                    if (selectedIndex < 0) {
                        VerificationCodeListScreen(
                            listState = listState,
                            onAccountClick = { selectedIndex = it },
                        )
                    } else {
                        var enterVisible by remember(selectedIndex) { mutableStateOf(false) }
                        LaunchedEffect(selectedIndex) { enterVisible = true }
                        SwipeToDismissBox(onDismissed = { selectedIndex = -1 }) { isBackground ->
                            if (isBackground) {
                                VerificationCodeListScreen(
                                    listState = listState,
                                    onAccountClick = { selectedIndex = it },
                                )
                            } else {
                                AnimatedVisibility(
                                    visible = enterVisible,
                                    enter = fadeIn(tween(DETAIL_ENTER_DURATION_MILLIS)) +
                                        scaleIn(
                                            initialScale = DETAIL_ENTER_INITIAL_SCALE,
                                            animationSpec = tween(DETAIL_ENTER_DURATION_MILLIS),
                                        ),
                                ) {
                                    VerificationCodeDetailPager(
                                        initialAccountIndex = selectedIndex,
                                        accounts = previewAccounts,
                                        clock = clock,
                                        favorites = favorites,
                                        onToggleFavorite = { index ->
                                            favorites[index] = favorites[index] != true
                                        },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    NoAccountsScreen()
                }
            }
        }
    }
}
