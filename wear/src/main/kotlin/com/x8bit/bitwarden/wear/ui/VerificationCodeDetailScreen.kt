package com.x8bit.bitwarden.wear.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.material3.VerticalPageIndicator
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import com.bitwarden.ui.platform.feature.settings.appearance.model.AppTheme
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R
import java.util.Date
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode
import com.x8bit.bitwarden.wear.ui.accounts.validOtpAt

private const val FAVORITE_FEEDBACK_MILLIS = 850L
private const val MILLIS_PER_SECOND = 1000L
private const val CODE_GROUP_SIZE = 3
private const val STANDARD_CODE_LENGTH = 6
private const val FAVORITE_INITIAL_SCALE = 0.4f

/** Displays one vertical page per account. */
@Composable
internal fun VerificationCodeDetailPager(
    initialAccountIndex: Int,
    accounts: List<WatchVerificationCode>,
    nowSeconds: Long,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onAccountSelected: (String) -> Unit,
) {
    val colors = BitwardenTheme.colorScheme
    val pagerState = rememberPagerState(
        initialPage = initialAccountIndex,
        pageCount = { accounts.size },
    )
    LaunchedEffect(pagerState, accounts.map { it.id }) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            accounts.getOrNull(page)?.let { onAccountSelected(it.id) }
        }
    }
    VerticalPagerScaffold(
        pagerState = pagerState,
        pageIndicator = {
            VerticalPageIndicator(
                pagerState = pagerState,
                selectedColor = colors.icon.secondary,
                unselectedColor = colors.icon.primary,
                backgroundColor = colors.background.primary,
            )
        },
    ) {
        VerticalPager(
            state = pagerState,
            flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(pagerState),
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                VerificationCodeDetailScreen(
                    account = accounts[page],
                    accountIndex = page,
                    nowSeconds = nowSeconds,
                    isFavorite = accounts[page].id in favorites,
                    onToggleFavorite = { onToggleFavorite(accounts[page].id) },
                )
            }
        }
    }
}

/** Shows the current SDK code and lets a double tap toggle its favorite state. */
@Suppress("LongParameterList")
@Composable
private fun VerificationCodeDetailScreen(
    account: WatchVerificationCode,
    accountIndex: Int,
    nowSeconds: Long,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val colors = BitwardenTheme.colorScheme
    val context = LocalContext.current
    var feedbackFavorite by remember(accountIndex) { mutableStateOf<Boolean?>(null) }
    var feedbackSequence by remember(accountIndex) { mutableIntStateOf(0) }
    LaunchedEffect(feedbackSequence) {
        if (feedbackSequence > 0) {
            delay(FAVORITE_FEEDBACK_MILLIS)
            feedbackFavorite = null
        }
    }
    val otp = account.validOtpAt(nowSeconds)
    val periodSeconds = otp?.periodSeconds ?: 1
    val secondsRemaining = if (otp == null) {
        0
    } else {
        (periodSeconds - nowSeconds % periodSeconds).toInt()
    }
    val clockText = DateFormat.getTimeFormat(context)
        .format(Date(nowSeconds * MILLIS_PER_SECOND))
    val favoriteActionLabel = stringResource(
        if (isFavorite) BitwardenString.unfavorite else BitwardenString.favorite,
    )
    val favoriteState = stringResource(
        if (isFavorite) R.string.added_to_favorites else R.string.not_in_favorites,
    )
    val toggleFavorite = {
        feedbackFavorite = !isFavorite
        feedbackSequence++
        onToggleFavorite()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.primary)
            .semantics(mergeDescendants = true) {
                stateDescription = favoriteState
                customActions = listOf(
                    CustomAccessibilityAction(label = favoriteActionLabel) {
                        toggleFavorite()
                        true
                    },
                )
            }
            .pointerInput(accountIndex, isFavorite) {
                detectTapGestures(onDoubleTap = { toggleFavorite() })
            },
    ) {
        DetailContent(
            account = account,
            code = otp?.code ?: "—",
            periodSeconds = periodSeconds,
            secondsRemaining = secondsRemaining,
            clockText = clockText,
            isFavorite = isFavorite,
        )
        FavoriteFeedback(feedbackFavorite = feedbackFavorite)
    }
}

@Composable
private fun BoxScope.FavoriteFeedback(feedbackFavorite: Boolean?) {
    val colors = BitwardenTheme.colorScheme
    AnimatedVisibility(
        visible = feedbackFavorite != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn() + scaleIn(initialScale = FAVORITE_INITIAL_SCALE),
        exit = fadeOut(),
    ) {
        Icon(
            painter = painterResource(
                if (feedbackFavorite == true) {
                    BitwardenDrawable.ic_favorite_full
                } else {
                    BitwardenDrawable.ic_favorite_empty
                },
            ),
            contentDescription = null,
            tint = if (feedbackFavorite == true) {
                colors.icon.secondary
            } else {
                colors.text.secondary
            },
            modifier = Modifier.size(68.dp),
        )
    }
}

@Composable
private fun DetailContent(
    account: WatchVerificationCode,
    code: String,
    secondsRemaining: Int,
    periodSeconds: Int,
    clockText: String,
    isFavorite: Boolean,
) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = clockText,
            color = colors.text.primary,
            style = typography.labelMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        AccountHeading(account = account, isFavorite = isFavorite)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = code.chunked(CODE_GROUP_SIZE).joinToString(" "),
            color = colors.text.codeBlue,
            style = typography.sensitiveInfoMedium.copy(
                fontSize = if (code.length > STANDARD_CODE_LENGTH) 24.sp else 30.sp,
                lineHeight = 34.sp,
            ),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CountdownRing(secondsRemaining = secondsRemaining, periodSeconds = periodSeconds)
    }
}

@Composable
private fun AccountHeading(account: WatchVerificationCode, isFavorite: Boolean) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        VerificationAccountIcon(account = account, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isFavorite) {
                Icon(
                    painter = painterResource(BitwardenDrawable.ic_favorite_full),
                    contentDescription = stringResource(BitwardenString.favorite),
                    tint = colors.icon.secondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = account.name,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE),
                color = colors.text.primary,
                style = typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = account.username.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE),
            color = colors.text.secondary,
            style = typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountdownRing(secondsRemaining: Int, periodSeconds: Int) {
    val colors = BitwardenTheme.colorScheme
    Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val ringSize = androidx.compose.ui.geometry.Size(
                width = size.width - stroke.width,
                height = size.height - stroke.width,
            )
            drawArc(
                color = colors.background.tertiary,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = ringSize,
                style = stroke,
            )
            drawArc(
                color = colors.icon.secondary,
                startAngle = -90f,
                sweepAngle = 360f * secondsRemaining /
                    periodSeconds.toFloat(),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = ringSize,
                style = stroke,
            )
        }
        Text(
            text = stringResource(R.string.time_remaining_seconds, secondsRemaining),
            color = colors.text.codeBlue,
            style = BitwardenTheme.typography.bodyMediumEmphasis,
        )
    }
}

@WearPreviewDevices
@Composable
private fun VerificationCodeDetailScreenPreview() {
    BitwardenTheme(theme = AppTheme.DARK) {
        VerificationCodeDetailPager(
            initialAccountIndex = 0,
            accounts = previewAccounts,
            nowSeconds = PREVIEW_TIME_SECONDS,
            favorites = emptySet(),
            onToggleFavorite = {},
            onAccountSelected = {},
        )
    }
}
