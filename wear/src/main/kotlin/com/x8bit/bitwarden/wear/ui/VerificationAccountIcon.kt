package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import com.bitwarden.ui.platform.components.icon.BitwardenIcon
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode

/** Uses the phone's icon policy, with an initial when no network icon is provided. */
@Composable
internal fun VerificationAccountIcon(account: WatchVerificationCode, modifier: Modifier) {
    val colors = BitwardenTheme.colorScheme
    Box(
        modifier = modifier.clip(CircleShape).background(colors.filledButton.background),
        contentAlignment = Alignment.Center,
    ) {
        if (account.iconUrl == null) {
            Text(
                text = account.name.take(1).uppercase(),
                color = colors.filledButton.foreground,
                style = BitwardenTheme.typography.bodyMediumEmphasis,
            )
        } else {
            BitwardenIcon(
                iconData = IconData.Network(account.iconUrl, BitwardenDrawable.ic_globe),
                modifier = modifier,
            )
        }
    }
}
