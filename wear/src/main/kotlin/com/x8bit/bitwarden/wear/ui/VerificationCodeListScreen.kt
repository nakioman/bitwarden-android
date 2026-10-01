package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R

/** A visual prototype of the verification-code account list. */
@Composable
internal fun VerificationCodeListScreen(
    listState: LazyListState,
    onAccountClick: (Int) -> Unit,
) {
    val colors = BitwardenTheme.colorScheme
    val accountsTitle = stringResource(R.string.accounts)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.primary)
            .semantics { paneTitle = accountsTitle },
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(previewAccounts) { index, account ->
            DemoAccountRow(account = account, onClick = { onAccountClick(index) })
        }
    }
}

@Composable
private fun DemoAccountRow(account: PreviewAccount, onClick: () -> Unit) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background.secondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
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
                style = typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = account.username,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = colors.text.secondary,
                style = typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

internal data class PreviewAccount(
    val name: String,
    val username: String,
    val initial: String,
    val codes: List<String>,
)

internal val previewAccounts = listOf(
    PreviewAccount(
        name = "GitHub",
        username = "usuario@example.com",
        initial = "G",
        codes = listOf("287461", "645092"),
    ),
    PreviewAccount(
        name = "Google",
        username = "usuario.muy.largo@example.com",
        initial = "G",
        codes = listOf("134982", "781530"),
    ),
    PreviewAccount(
        name = "Microsoft Entra ID empresarial",
        username = "usuario@example.com",
        initial = "M",
        codes = listOf("523761", "094216"),
    ),
    PreviewAccount(
        name = "Discord",
        username = "usuario@example.com",
        initial = "D",
        codes = listOf("805214", "370619"),
    ),
)
