package com.x8bit.bitwarden.data.wear

/** Records explicit consent and pins public keys without transferring verification data. */
interface WearConsentManager {
    /**
     * Loads devices for the active, unlocked [userId] and verifies reachable authorized keys.
     * Changed keys fail closed; existing authorization is retained for retry or revocation.
     */
    suspend fun getDevices(userId: String): WearConsentResult

    /** Records [enabled] for a device after revalidating the account and device. */
    suspend fun setConsent(userId: String, deviceId: String, enabled: Boolean): WearConsentResult
}

/** A device's saved consent and reachability for the selected account. */
data class WearConsentDevice(
    val id: String,
    val name: String,
    val isAuthorized: Boolean,
    val isReachable: Boolean,
)

/** Outcomes that do not expose platform exceptions or credentials to the UI. */
sealed class WearConsentResult {
    /** The current device list. */
    data class Success(val devices: List<WearConsentDevice>) : WearConsentResult()

    /** The requested account is no longer active and unlocked. */
    data object VaultLocked : WearConsentResult()

    /** Wear support is unavailable in this build. */
    data object Unavailable : WearConsentResult()

    /** Discovery or persistence failed; no new authorization should be assumed. */
    data object Error : WearConsentResult()
}
