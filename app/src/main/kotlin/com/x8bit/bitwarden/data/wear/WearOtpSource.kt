package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearOtpEntry

/** Prepares OTP entries for an authorized companion of the active, unlocked account. */
interface WearOtpSource {
    /**
     * Returns the complete eligible list, or a failure without a partial list. Excludes deleted,
     * archived and password-reprompt items, and respects the account's TOTP entitlement.
     * The caller must encrypt the result immediately and recheck authorization before sending.
     */
    suspend fun getEntries(userId: String, deviceId: String): Result<List<WearOtpEntry>>
}
