package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Persists account-scoped consent independently of the watch connection. */
interface WearConsentDiskSource {
    /** Signals successful persisted changes; subscribers must reload the current ledger. */
    val changes: Flow<Unit>

    /** Returns saved decisions, or a failure if they cannot be read safely. */
    fun getConsents(): Result<List<WearConsent>>

    /**
     * Atomically persists [updated] only if the current ledger still matches [expected].
     * A conflicting or unreadable ledger fails without changing any decisions.
     */
    fun compareAndSetConsents(
        expected: List<WearConsent>,
        updated: List<WearConsent>,
    ): Result<Unit>
}

/** User consent for a specific account and companion. No vault keys or TOTP data are stored. */
@Serializable
data class WearConsent(
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val isAuthorized: Boolean,
    /** Pinned X.509 key encoded as Base64; retained when consent is revoked. */
    val publicKey: String? = null,
)
