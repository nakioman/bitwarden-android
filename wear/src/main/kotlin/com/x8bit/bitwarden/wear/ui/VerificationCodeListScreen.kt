package com.x8bit.bitwarden.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode

/** Displays the synchronized verification-code accounts. */
@Composable
internal fun VerificationCodeListScreen(
    accounts: List<WatchVerificationCode>,
    listState: LazyListState,
    onAccountClick: (String) -> Unit,
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
        itemsIndexed(accounts, key = { _, account -> account.id }) { _, account ->
            AccountRow(account = account, onClick = { onAccountClick(account.id) })
        }
    }
}

@Composable
private fun AccountRow(account: WatchVerificationCode, onClick: () -> Unit) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background.secondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VerificationAccountIcon(account = account, modifier = Modifier.size(30.dp))
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
                text = account.username.orEmpty(),
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = colors.text.secondary,
                style = typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun VerificationCodeListScreenPreview() {
    BitwardenTheme(theme = AppTheme.DARK) {
        VerificationCodeListScreen(
            accounts = previewAccounts,
            listState = rememberLazyListState(),
            onAccountClick = {},
        )
    }
}
