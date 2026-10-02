package com.x8bit.bitwarden.data.wear

import java.io.IOException

private const val MAX_WITHDRAWAL_ATTEMPTS = 3

/** Uses bounded atomic updates so concurrent enrollment cannot erase a logout withdrawal. */
class WearConsentRevokerImpl(
    private val consentSource: WearConsentDiskSource,
    private val isEnabled: Boolean = WearSyncAvailability.isEnabled,
) : WearConsentRevoker {
    override fun revokeAccount(userId: String): Result<Unit> {
        if (!isEnabled) return Result.success(Unit)
        repeat(MAX_WITHDRAWAL_ATTEMPTS) {
            val current = consentSource.getConsents().getOrElse { return unavailable() }
            val updated = current.map {
                if (it.userId == userId) it.copy(isAuthorized = false) else it
            }
            if (updated == current) return Result.success(Unit)
            if (consentSource.compareAndSetConsents(current, updated).isSuccess) {
                return Result.success(Unit)
            }
        }
        return unavailable()
    }
}

private fun unavailable(): Result<Unit> = Result.failure(IOException("Wear withdrawal unavailable"))
