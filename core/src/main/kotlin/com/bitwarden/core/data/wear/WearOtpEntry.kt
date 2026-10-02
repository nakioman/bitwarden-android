package com.bitwarden.core.data.wear

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
