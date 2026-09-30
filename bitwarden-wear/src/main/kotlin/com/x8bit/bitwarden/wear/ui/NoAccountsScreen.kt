package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x8bit.bitwarden.wear.R

/**
 * Displays the empty account state until accounts are available on the watch.
 */
@Composable
internal fun NoAccountsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_shield_icon),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
        )
        BasicText(
            text = stringResource(R.string.no_accounts_available),
            modifier = Modifier.padding(horizontal = 24.dp),
            style = TextStyle(
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
