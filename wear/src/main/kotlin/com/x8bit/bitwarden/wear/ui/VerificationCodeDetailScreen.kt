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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.x8bit.bitwarden.wear.R
import java.util.Date
import kotlinx.coroutines.delay

private const val PREVIEW_CODE_PERIOD_SECONDS = 30L
private const val FAVORITE_FEEDBACK_MILLIS = 850L
private const val MILLIS_PER_SECOND = 1000L
private const val CODE_GROUP_SIZE = 3
private const val FAVORITE_INITIAL_SCALE = 0.4f
private const val VIRTUAL_PAGE_COUNT = Int.MAX_VALUE

/** Keeps the vertical swipe moving through all accounts, including either end. */
@Composable
internal fun VerificationCodeDetailPager(
    initialAccountIndex: Int,
    accounts: List<PreviewAccount>,
    favorites: Map<Int, Boolean>,
    onToggleFavorite: (Int) -> Unit,
) {
    val initialPage = (VIRTUAL_PAGE_COUNT / 2 / accounts.size) * accounts.size
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { VIRTUAL_PAGE_COUNT },
    )
    VerticalPager(state = pagerState) { page ->
        // Swiping up advances the virtual page and selects the next account.
        val accountIndex = Math.floorMod(initialAccountIndex + (page - initialPage), accounts.size)
        AnimatedPage(pageIndex = page, pagerState = pagerState) {
            VerificationCodeDetailScreen(
                account = accounts[accountIndex],
                accountIndex = accountIndex,
                accountCount = accounts.size,
                isFavorite = favorites[accountIndex] == true,
                onToggleFavorite = { onToggleFavorite(accountIndex) },
            )
        }
    }
}

/** Shows a preview code and lets a double tap toggle its favorite state. */
@Suppress("LongParameterList")
@Composable
private fun VerificationCodeDetailScreen(
    account: PreviewAccount,
    accountIndex: Int,
    accountCount: Int,
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
    val nowSeconds = rememberPreviewTimeSeconds()
    val secondsRemaining = (PREVIEW_CODE_PERIOD_SECONDS -
        nowSeconds % PREVIEW_CODE_PERIOD_SECONDS).toInt()
    val codeIndex = ((nowSeconds / PREVIEW_CODE_PERIOD_SECONDS) % account.codes.size).toInt()
    val clockText = DateFormat.getTimeFormat(context)
        .format(Date(nowSeconds * MILLIS_PER_SECOND))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.primary)
            .pointerInput(accountIndex, isFavorite) {
                detectTapGestures(onDoubleTap = {
                    feedbackFavorite = !isFavorite
                    feedbackSequence++
                    onToggleFavorite()
                })
            },
    ) {
        DetailContent(
            account = account,
            code = account.codes[codeIndex],
            secondsRemaining = secondsRemaining,
            clockText = clockText,
        )
        AccountPositionDots(
            accountIndex = accountIndex,
            accountCount = accountCount,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
        )
        FavoriteIndicators(isFavorite = isFavorite, feedbackFavorite = feedbackFavorite)
    }
}

@Composable
private fun rememberPreviewTimeSeconds(): Long {
    val nowSeconds by produceState(
        initialValue = System.currentTimeMillis() / MILLIS_PER_SECOND,
    ) {
        while (true) {
            delay(MILLIS_PER_SECOND)
            value = System.currentTimeMillis() / MILLIS_PER_SECOND
        }
    }
    return nowSeconds
}

@Composable
private fun BoxScope.FavoriteIndicators(isFavorite: Boolean, feedbackFavorite: Boolean?) {
    val colors = BitwardenTheme.colorScheme
    if (isFavorite) {
        Icon(
            painter = painterResource(BitwardenDrawable.ic_favorite_full),
            contentDescription = stringResource(BitwardenString.favorite),
            tint = colors.icon.secondary,
            modifier = Modifier.align(Alignment.TopStart)
                .padding(start = 27.dp, top = 47.dp)
                .size(15.dp),
        )
    }
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
    account: PreviewAccount,
    code: String,
    secondsRemaining: Int,
    clockText: String,
) {
    val colors = BitwardenTheme.colorScheme
    val typography = BitwardenTheme.typography
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = clockText,
            color = colors.text.primary,
            style = typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(colors.filledButton.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = account.initial,
                color = colors.filledButton.foreground,
                style = typography.bodyMediumEmphasis,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = account.name,
            modifier = Modifier.widthIn(max = 140.dp).basicMarquee(iterations = Int.MAX_VALUE),
            color = colors.text.primary,
            style = typography.bodyMediumEmphasis.copy(fontSize = 14.sp, lineHeight = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
        Text(
            text = account.username,
            modifier = Modifier.widthIn(max = 140.dp).basicMarquee(iterations = Int.MAX_VALUE),
            color = colors.text.secondary,
            style = typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = code.chunked(CODE_GROUP_SIZE).joinToString(" "),
            color = colors.text.codeBlue,
            style = typography.sensitiveInfoMedium.copy(fontSize = 30.sp, lineHeight = 34.sp),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CountdownRing(secondsRemaining = secondsRemaining)
    }
}

@Composable
private fun CountdownRing(secondsRemaining: Int) {
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
                    PREVIEW_CODE_PERIOD_SECONDS.toFloat(),
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

@Composable
private fun AccountPositionDots(
    accountIndex: Int,
    accountCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = BitwardenTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(accountCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == accountIndex) 7.dp else 4.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == accountIndex) colors.icon.secondary else colors.icon.primary,
                    ),
            )
        }
    }
}
