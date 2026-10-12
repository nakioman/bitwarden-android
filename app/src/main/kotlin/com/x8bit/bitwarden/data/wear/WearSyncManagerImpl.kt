package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.data.auth.repository.AuthRepository

/**
 * @property authRepository Active account identity.
 * @property accountSource Active, logged in and unlocked account guard.
 * @property diskSource Per-account sync setting.
 * @property otpSource Transient OTP extraction from the unlocked vault.
 * @property snapshotStore Encrypted snapshot publication.
 */
class WearSyncManagerImpl(
    private val authRepository: AuthRepository,
    private val accountSource: WearOtpAccountSource,
    private val diskSource: WearSyncDiskSource,
    private val otpSource: WearOtpSource,
    private val snapshotStore: WearSnapshotStore,
) : WearSyncManager {
    override fun isSyncEnabled(userId: String): Boolean = diskSource.isSyncEnabled(userId)

    override suspend fun setSyncEnabled(userId: String, isEnabled: Boolean): Result<Unit> {
        diskSource.storeSyncEnabled(userId, isEnabled)
        return if (isEnabled) sync() else snapshotStore.remove(userId)
    }

    override suspend fun sync(): Result<Unit> {
        val userId = authRepository.userStateFlow.value?.activeUserId
            ?: return Result.success(Unit)
        if (!canPublish(userId)) return Result.success(Unit)
        val entries = otpSource.getEntries(userId).getOrElse { return Result.failure(it) }
        return snapshotStore.publish(userId, entries) { canPublish(userId) }
    }

    private fun canPublish(userId: String): Boolean =
        diskSource.isSyncEnabled(userId) && accountSource.getAccount(userId) != null
}
