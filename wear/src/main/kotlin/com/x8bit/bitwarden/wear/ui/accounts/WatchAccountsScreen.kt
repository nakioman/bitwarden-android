package com.x8bit.bitwarden.wear.ui.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.SwipeToDismissBox
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.ui.NoAccountsScreen
import com.x8bit.bitwarden.wear.ui.VerificationCodeDetailPager
import com.x8bit.bitwarden.wear.ui.VerificationCodeListScreen
import com.x8bit.bitwarden.wear.ui.security.WatchSecurityScreen

private const val DETAIL_ENTER_DURATION_MILLIS = 220
private const val DETAIL_ENTER_INITIAL_SCALE = 0.82f

/** Connects the approved list and detail UI to protected offline accounts. */
@Composable
internal fun WatchAccountsScreen(
    onOpenSettings: () -> Unit,
    viewModel: WatchAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.trySendAction(WatchAccountsAction.Resume)
        onPauseOrDispose { viewModel.trySendAction(WatchAccountsAction.Pause) }
    }
    when (state.status) {
        WatchSecurityStatus.UNVERIFIED -> Box(
            modifier = Modifier.fillMaxSize()
                .background(BitwardenTheme.colorScheme.background.primary),
        )
        WatchSecurityStatus.LOCKED, WatchSecurityStatus.SCREEN_LOCK_REQUIRED -> {
            WatchSecurityScreen(
                isScreenLockRequired = state.status == WatchSecurityStatus.SCREEN_LOCK_REQUIRED,
                onOpenSettings = onOpenSettings,
            )
        }
        WatchSecurityStatus.UNLOCKED -> when (state.viewState) {
            WatchAccountsViewState.EMPTY -> NoAccountsScreen()
            WatchAccountsViewState.LOADING -> AccountsStatusScreen(isError = false)
            WatchAccountsViewState.ERROR -> AccountsStatusScreen(
                isError = true,
                onRetry = { viewModel.trySendAction(WatchAccountsAction.Retry) },
            )
            WatchAccountsViewState.CONTENT -> AccountsContent(
                state = state,
                onAction = viewModel::trySendAction,
            )
        }
    }
}

@Composable
private fun AccountsContent(state: WatchAccountsState, onAction: (WatchAccountsAction) -> Unit) {
    val listState = rememberLazyListState()
    val selectedIndex = state.accounts.indexOfFirst { it.id == state.selectedId }
    BackHandler(enabled = selectedIndex >= 0) { onAction(WatchAccountsAction.Back) }
    val list: @Composable () -> Unit = {
        VerificationCodeListScreen(
            accounts = state.accounts,
            listState = listState,
            onAccountClick = { onAction(WatchAccountsAction.Select(it)) },
        )
    }
    if (selectedIndex < 0) {
        list()
    } else {
        var enterVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { enterVisible = true }
        SwipeToDismissBox(onDismissed = { onAction(WatchAccountsAction.Back) }) { isBackground ->
            if (isBackground) {
                list()
            } else {
                AnimatedVisibility(
                    visible = enterVisible,
                    enter = fadeIn(tween(DETAIL_ENTER_DURATION_MILLIS)) +
                        scaleIn(
                            initialScale = DETAIL_ENTER_INITIAL_SCALE,
                            animationSpec = tween(DETAIL_ENTER_DURATION_MILLIS),
                        ),
                ) {
                    key(state.accounts.map { it.id }) {
                        VerificationCodeDetailPager(
                            initialAccountIndex = selectedIndex,
                            accounts = state.accounts,
                            nowSeconds = state.nowSeconds,
                            favorites = state.favoriteIds,
                            onToggleFavorite = { onAction(WatchAccountsAction.ToggleFavorite(it)) },
                            onAccountSelected = { onAction(WatchAccountsAction.Select(it)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountsStatusScreen(isError: Boolean, onRetry: () -> Unit = {}) {
    val colors = BitwardenTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background.primary)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(
                if (isError) BitwardenString.an_error_has_occurred else BitwardenString.loading,
            ),
            color = colors.text.primary,
            style = BitwardenTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (isError) {
            BitwardenFilledButton(
                label = stringResource(BitwardenString.try_again),
                onClick = onRetry,
            )
        }
    }
}
