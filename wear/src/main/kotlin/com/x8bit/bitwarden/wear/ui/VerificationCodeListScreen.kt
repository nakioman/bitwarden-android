package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R

/** A visual prototype of the verification-code account list. */
@Composable
internal fun VerificationCodeListScreen() {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.primary),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.accounts),
                modifier = Modifier.fillMaxWidth(),
                color = colors.text.primary,
                style = typography.bodyMediumEmphasis,
                textAlign = TextAlign.Center,
            )
        }
        items(demoAccounts) { account ->
            DemoAccountRow(account = account)
        }
    }
}

@Composable
private fun DemoAccountRow(account: DemoAccount) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background.secondary)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(colors.filledButton.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account.initial,
                color = colors.filledButton.foreground,
                style = typography.bodyMediumEmphasis,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = colors.text.primary,
                style = typography.bodyMediumEmphasis.copy(fontSize = 12.sp, lineHeight = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = account.username,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = colors.text.secondary,
                style = typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private data class DemoAccount(
    val name: String,
    val username: String,
    val initial: String,
)

private val demoAccounts = listOf(
    DemoAccount(name = "GitHub", username = "usuario@example.com", initial = "G"),
    DemoAccount(name = "Google", username = "usuario.muy.largo@example.com", initial = "G"),
    DemoAccount(
        name = "Microsoft Entra ID empresarial",
        username = "usuario@example.com",
        initial = "M",
    ),
    DemoAccount(name = "Discord", username = "usuario@example.com", initial = "D"),
)
