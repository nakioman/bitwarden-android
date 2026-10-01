package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Guards consent with the active account and retains revocations for later synchronization. */
class WearConsentManagerImpl(
    private val deviceSource: WearDeviceSource,
    private val diskSource: WearConsentDiskSource,
    private val authRepository: AuthRepository,
    private val vaultLockManager: VaultLockManager,
    private val dispatcherManager: DispatcherManager,
) : WearConsentManager {
    private val mutex = Mutex()

    override suspend fun getDevices(userId: String): WearConsentResult =
        withContext(dispatcherManager.io) {
            mutex.withLock { loadDevices(userId) }
        }

    override suspend fun setConsent(
        userId: String,
        deviceId: String,
        enabled: Boolean,
    ): WearConsentResult = withContext(dispatcherManager.io) {
        mutex.withLock {
            val result = loadDevices(userId, requireDiscovery = enabled)
            if (result !is WearConsentResult.Success) return@withLock result
            val device = result.devices.find { it.id == deviceId }
                ?: return@withLock WearConsentResult.Error
            if (enabled && !device.isReachable) return@withLock WearConsentResult.Error
            val consents = diskSource.getConsents().getOrNull()
                ?: return@withLock WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return@withLock WearConsentResult.VaultLocked
            val updated = consents.filterNot { it.userId == userId && it.deviceId == deviceId } +
                WearConsent(userId, device.id, device.name, enabled)
            if (diskSource.storeConsents(updated).isFailure) return@withLock WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return@withLock WearConsentResult.VaultLocked
            WearConsentResult.Success(
                result.devices.map {
                    if (it.id == deviceId) it.copy(isAuthorized = enabled) else it
                },
            )
        }
    }

    private suspend fun loadDevices(
        userId: String,
        requireDiscovery: Boolean = true,
    ): WearConsentResult {
        if (!deviceSource.isSupported) return WearConsentResult.Unavailable
        if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
        val discovered = deviceSource.getDevices().getOrNull()
        if (discovered == null && requireDiscovery) return WearConsentResult.Error
        val devices = discovered.orEmpty()
        val consents = diskSource.getConsents().getOrNull() ?: return WearConsentResult.Error
        if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
        val userConsents = consents.filter { it.userId == userId }
        val reachable = devices.map { device ->
            WearConsentDevice(
                id = device.id,
                name = device.name,
                isAuthorized = userConsents.any { it.deviceId == device.id && it.isAuthorized },
                isReachable = true,
            )
        }
        val offline = userConsents.filter { saved -> devices.none { it.id == saved.deviceId } }
            .map { WearConsentDevice(it.deviceId, it.deviceName, it.isAuthorized, false) }
        return WearConsentResult.Success((reachable + offline).sortedBy { it.name })
    }

    private fun isAccountUnlocked(userId: String): Boolean =
        authRepository.userStateFlow.value?.activeUserId == userId &&
            vaultLockManager.isVaultUnlocked(userId)
}
