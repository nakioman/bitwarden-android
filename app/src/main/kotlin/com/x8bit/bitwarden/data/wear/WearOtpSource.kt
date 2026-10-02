package com.x8bit.bitwarden.data.wear

/** Prepares OTP entries for an authorized companion of the active, unlocked account. */
interface WearOtpSource {
    /**
     * Returns the complete eligible list, or a failure without a partial list. Excludes deleted,
     * archived and password-reprompt items, and respects the account's TOTP entitlement.
     * The caller must encrypt the result immediately and recheck authorization before sending.
     */
    suspend fun getEntries(userId: String, deviceId: String): Result<List<WearOtpEntry>>
}

/**
 * Sensitive, transient OTP data. Must never be logged, saved in UI state or persisted unencrypted.
 *
 * @property id Vault item identifier.
 * @property name Display name.
 * @property username Optional login username.
 * @property secret Original TOTP secret or URI, preserving the SDK-supported format.
 * @property iconUrl Optional website icon URL. Null means use a local fallback.
 */
class WearOtpEntry(
    val id: String,
    val name: String,
    val username: String?,
    val secret: String,
    val iconUrl: String? = null,
) {
    override fun toString(): String = "WearOtpEntry(<redacted>)"
}
