package com.x8bit.bitwarden.wear.data.otp

import java.time.Instant

/** Reads the latest offline accounts and their codes only while the watch is securely unlocked. */
interface WatchVerificationCodeSource {
    /** Returns no seeds; an invalid OTP has a null code rather than a fabricated value. */
    suspend fun read(): Result<List<WatchVerificationCode>>
}

/**
 * Ephemeral presentation data. This model must not be written to disk or saved in a Bundle.
 *
 * @property id Stable vault item identifier.
 * @property name Original vault item name.
 * @property username Optional login username.
 * @property iconUrl Icon URL selected by the phone's existing icon policy.
 * @property otp Generated code, or null when the SDK rejected this item's OTP input.
 * @property generatedAt Time supplied to the generator, used to derive expiry and countdown.
 */
data class WatchVerificationCode(
    val id: String,
    val name: String,
    val username: String?,
    val iconUrl: String?,
    val otp: WatchOtpCode?,
    val generatedAt: Instant,
) {
    override fun toString(): String = "WatchVerificationCode(otpAvailable=${otp != null})"
}
