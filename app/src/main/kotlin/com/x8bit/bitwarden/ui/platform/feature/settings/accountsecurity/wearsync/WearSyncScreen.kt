package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

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
import com.bitwarden.ui.platform.components.dialog.BitwardenTwoButtonDialog
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenTextRow
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme

/** Displays reachable watches and explicit, account-scoped consent. */
@Composable
fun WearSyncScreen(
    onNavigateBack: () -> Unit,
    viewModel: WearSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.trySendAction(WearSyncAction.Refresh)
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
    WearSyncDialog(state = state, onAction = onAction)
    BitwardenScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(BitwardenString.wear_os_sync),
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
                navigationIcon = rememberVectorPainter(BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(BitwardenString.back),
                onNavigationIconClick = { onAction(WearSyncAction.Back) },
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
            Text(
                text = stringResource(
                    BitwardenString.choose_a_watch_for_offline_verification_codes,
                ),
                color = BitwardenTheme.colorScheme.text.primary,
                style = BitwardenTheme.typography.bodyMedium,
            )
            WearSyncDeviceList(state = state, onAction = onAction)
            BitwardenOutlinedButton(
                label = stringResource(BitwardenString.refresh_watches),
                onClick = { onAction(WearSyncAction.Refresh) },
                isEnabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WearSyncDeviceList(state: WearSyncState, onAction: (WearSyncAction) -> Unit) {
    val message = when {
        state.isLoading -> BitwardenString.loading
        state.error == WearSyncError.VAULT_LOCKED -> {
            BitwardenString.unlock_your_vault_to_authorize_a_watch
        }
        state.error == WearSyncError.UNAVAILABLE -> BitwardenString.wear_os_sync_is_unavailable
        state.error == WearSyncError.FAILURE -> BitwardenString.unable_to_load_watch_settings
        state.devices.isEmpty() -> BitwardenString.no_bitwarden_watches_connected
        else -> null
    }
    if (message != null) {
        Text(
            text = stringResource(message),
            color = BitwardenTheme.colorScheme.text.primary,
            style = BitwardenTheme.typography.bodyMedium,
        )
    } else {
        state.devices.forEach { device ->
            val status = stringResource(
                if (device.isAuthorized) {
                    BitwardenString.watch_authorized
                } else {
                    BitwardenString.watch_not_authorized
                },
            )
            val connection = stringResource(
                if (device.isReachable) {
                    BitwardenString.watch_connected
                } else {
                    BitwardenString.watch_disconnected
                },
            )
            BitwardenTextRow(
                text = device.name,
                description = AnnotatedString(
                    stringResource(BitwardenString.watch_status_format, status, connection),
                ),
                onClick = { onAction(WearSyncAction.DeviceClick(device.id)) },
                isEnabled = device.isReachable || device.isAuthorized,
                cardStyle = CardStyle.Full,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WearSyncDialog(state: WearSyncState, onAction: (WearSyncAction) -> Unit) {
    state.pendingDevice?.let { device ->
        val actionLabel = stringResource(
            if (device.isAuthorized) {
                BitwardenString.revoke_watch_access
            } else {
                BitwardenString.authorize_watch
            },
        )
        BitwardenTwoButtonDialog(
            title = device.name,
            message = stringResource(
                if (device.isAuthorized) {
                    BitwardenString.watch_codes_will_be_removed_when_the_watch_reconnects
                } else {
                    BitwardenString.allow_this_watch_to_store_verification_codes_for_offline_use
                },
            ),
            confirmButtonText = actionLabel,
            dismissButtonText = stringResource(BitwardenString.cancel),
            onConfirmClick = { onAction(WearSyncAction.Confirm) },
            onDismissClick = { onAction(WearSyncAction.Dismiss) },
            onDismissRequest = { onAction(WearSyncAction.Dismiss) },
        )
    }
}

@Preview
@Composable
private fun WearSyncConnectedPreview() {
    BitwardenTheme(theme = AppTheme.DARK) {
        WearSyncContent(
            state = WearSyncState(
                devices = listOf(WearSyncDeviceState("preview", "Wear OS watch", false, true)),
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun WearSyncEmptyPreview() {
    BitwardenTheme(theme = AppTheme.LIGHT) {
        WearSyncContent(state = WearSyncState(), onAction = {})
    }
}
