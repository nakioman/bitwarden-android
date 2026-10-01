package com.x8bit.bitwarden.wear.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme

/** Explains the secure screen lock requirement using the shared Bitwarden theme. */
@Composable
internal fun WatchSecurityScreen(
    isScreenLockRequired: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BitwardenTheme.colorScheme.background.primary)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (isScreenLockRequired) {
                    BitwardenString.set_up_a_screen_lock_on_your_watch_to_use_bitwarden
                } else {
                    BitwardenString.unlock_your_watch_to_use_bitwarden
                },
            ),
            color = BitwardenTheme.colorScheme.text.primary,
            style = BitwardenTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (isScreenLockRequired) {
            BitwardenFilledButton(
                label = stringResource(BitwardenString.settings),
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun WatchSecurityScreenPreview() {
    BitwardenTheme(theme = AppTheme.DARK) {
        WatchSecurityScreen(isScreenLockRequired = true, onOpenSettings = {})
    }
}
