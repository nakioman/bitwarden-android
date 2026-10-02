package com.x8bit.bitwarden.wear.data.otp

import java.time.Instant

/** Generates verification codes locally without requiring the phone or an authenticated session. */
interface WatchOtpGenerator {
    /** Accepts the original Base32 key, otpauth URI or Steam URI without normalizing it. */
    suspend fun generate(secret: String, time: Instant): Result<WatchOtpCode>
}

/** A generated code and its actual period, without the seed used to generate it. */
data class WatchOtpCode(
    val code: String,
    val periodSeconds: Int,
) {
    override fun toString(): String = "WatchOtpCode(periodSeconds=$periodSeconds)"
}
