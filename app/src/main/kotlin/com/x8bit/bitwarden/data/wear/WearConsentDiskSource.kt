package com.x8bit.bitwarden.data.wear

import kotlinx.serialization.Serializable

/** Persists account-scoped consent independently of the watch connection. */
interface WearConsentDiskSource {
    /** Returns saved decisions, or a failure if they cannot be read safely. */
    fun getConsents(): Result<List<WearConsent>>

    /** Persists decisions before reporting success. Revocations remain in the ledger. */
    fun storeConsents(consents: List<WearConsent>): Result<Unit>
}

/** User consent for a specific account and companion. No vault keys or TOTP data are stored. */
@Serializable
data class WearConsent(
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val isAuthorized: Boolean,
)
