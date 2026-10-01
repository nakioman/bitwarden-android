package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R

/**
 * Displays the empty account state until accounts are available on the watch.
 */
@Composable
internal fun NoAccountsScreen() {
    val colors = BitwardenTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.primary),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_shield_icon),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_accounts_available),
            modifier = Modifier.padding(horizontal = 24.dp),
            color = colors.text.primary,
            style = BitwardenTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
