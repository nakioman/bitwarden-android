package com.x8bit.bitwarden.wear.ui

import com.x8bit.bitwarden.wear.data.otp.WatchOtpCode
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode
import java.time.Instant

internal const val PREVIEW_TIME_SECONDS = 1790762892L
private const val DEFAULT_PERIOD_SECONDS = 30
private const val LONG_PERIOD_SECONDS = 60

// Synthetic data referenced only by Compose preview functions.
internal val previewAccounts = listOf(
    WatchVerificationCode(
        "preview-github", "GitHub", "usuario@example.com", null,
        WatchOtpCode("287461", DEFAULT_PERIOD_SECONDS), Instant.ofEpochSecond(PREVIEW_TIME_SECONDS),
    ),
    WatchVerificationCode(
        "preview-google", "Google", "usuario.muy.largo@example.com", null,
        WatchOtpCode("13498298", LONG_PERIOD_SECONDS), Instant.ofEpochSecond(PREVIEW_TIME_SECONDS),
    ),
    WatchVerificationCode(
        "preview-microsoft", "Microsoft Entra ID empresarial", "usuario@example.com", null,
        WatchOtpCode("523761", DEFAULT_PERIOD_SECONDS), Instant.ofEpochSecond(PREVIEW_TIME_SECONDS),
    ),
)
