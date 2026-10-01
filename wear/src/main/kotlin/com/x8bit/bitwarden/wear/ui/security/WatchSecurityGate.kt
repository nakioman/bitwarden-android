package com.x8bit.bitwarden.wear.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus

/** Displays [content] only after the system confirms a secure, unlocked watch. */
@Composable
internal fun WatchSecurityGate(
    onOpenSettings: () -> Unit,
    viewModel: WatchSecurityViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.trySendAction(WatchSecurityAction.Resume)
        onPauseOrDispose {
            viewModel.trySendAction(WatchSecurityAction.Pause)
        }
    }
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            WatchSecurityEvent.OpenSettings -> onOpenSettings()
        }
    }
    when (state.status) {
        WatchSecurityStatus.UNVERIFIED -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BitwardenTheme.colorScheme.background.primary),
        )

        WatchSecurityStatus.SCREEN_LOCK_REQUIRED, WatchSecurityStatus.LOCKED -> {
            WatchSecurityScreen(
                isScreenLockRequired = state.status == WatchSecurityStatus.SCREEN_LOCK_REQUIRED,
                onOpenSettings = {
                    viewModel.trySendAction(WatchSecurityAction.OpenSettings)
                },
            )
        }

        WatchSecurityStatus.UNLOCKED -> content()
    }
}
