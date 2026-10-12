package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.header.BitwardenListHeaderText
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenTextRow
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.toggle.BitwardenSwitch
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme

/** Lets the active account turn watch sync on or off and shows watches running Bitwarden. */
@Composable
fun WearSyncScreen(
    onNavigateBack: () -> Unit,
    viewModel: WearSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.trySendAction(WearSyncAction.RefreshClick)
        onPauseOrDispose { }
    }
    EventsEffect(viewModel = viewModel) {
        when (it) {
            WearSyncEvent.NavigateBack -> onNavigateBack()
        }
    }
    WearSyncContent(state = state, onAction = viewModel::trySendAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WearSyncContent(state: WearSyncState, onAction: (WearSyncAction) -> Unit) {
    BitwardenScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(BitwardenString.wear_os_sync),
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
                navigationIcon = rememberVectorPainter(BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(BitwardenString.back),
                onNavigationIconClick = { onAction(WearSyncAction.BackClick) },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .standardHorizontalMargin()
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BitwardenSwitch(
                label = stringResource(BitwardenString.sync_verification_codes_to_watch),
                supportingText = stringResource(
                    BitwardenString.your_verification_codes_are_encrypted_and_stored_on_your_watch,
                ),
                isChecked = state.isSyncEnabled,
                onCheckedChange = { onAction(WearSyncAction.SyncToggle(it)) },
                enabled = state.error != WearSyncError.UNAVAILABLE,
                cardStyle = CardStyle.Full,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.error == WearSyncError.UPDATE_FAILED) {
                WearSyncMessage(BitwardenString.unable_to_update_your_watches_please_try_again)
            }
            BitwardenListHeaderText(label = stringResource(BitwardenString.watches))
            WearSyncWatchList(state = state)
            BitwardenOutlinedButton(
                label = stringResource(BitwardenString.refresh_watches),
                onClick = { onAction(WearSyncAction.RefreshClick) },
                isEnabled = !state.isLoadingWatches,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WearSyncWatchList(state: WearSyncState) {
    val message = when {
        state.isLoadingWatches -> BitwardenString.loading
        state.error == WearSyncError.UNAVAILABLE -> BitwardenString.wear_os_sync_is_unavailable
        state.error == WearSyncError.LOAD_FAILED -> BitwardenString.unable_to_load_watch_settings
        state.watches.isEmpty() -> BitwardenString.no_bitwarden_watches_connected
        else -> null
    }
    if (message != null) {
        WearSyncMessage(message)
        return
    }
    state.watches.forEach { watch ->
        BitwardenTextRow(
            text = watch.name,
            description = AnnotatedString(stringResource(BitwardenString.watch_connected)),
            onClick = {},
            clickable = false,
            cardStyle = CardStyle.Full,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WearSyncMessage(@StringRes message: Int) {
    Text(
        text = stringResource(message),
        color = BitwardenTheme.colorScheme.text.primary,
        style = BitwardenTheme.typography.bodyMedium,
    )
}

@Preview
@Composable
private fun WearSyncConnectedPreview() {
    BitwardenTheme(theme = AppTheme.DARK) {
        WearSyncContent(
            state = WearSyncState(
                isSyncEnabled = true,
                watches = listOf(WearSyncWatchState("preview", "Wear OS watch")),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun WearSyncEmptyPreview() {
    BitwardenTheme(theme = AppTheme.LIGHT) {
        WearSyncContent(state = WearSyncState(isSyncEnabled = false), onAction = {})
    }
}
