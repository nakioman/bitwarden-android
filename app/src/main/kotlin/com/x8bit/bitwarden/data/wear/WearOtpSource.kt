package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearOtpEntry

/** Prepares OTP entries of the active, unlocked account for watch sync. */
interface WearOtpSource {
    /**
     * Returns the complete eligible list, or a failure without a partial list. Excludes deleted,
     * archived and password-reprompt items, and respects the account's TOTP entitlement.
     * The caller must encrypt the result immediately and never persist or log it.
     */
    suspend fun getEntries(userId: String): Result<List<WearOtpEntry>>
}
