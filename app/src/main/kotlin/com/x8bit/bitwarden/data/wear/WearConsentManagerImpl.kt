package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Base64

/** Guards consent with the active account and retains revocations for later synchronization. */
class WearConsentManagerImpl(
    private val deviceSource: WearDeviceSource,
    private val diskSource: WearConsentDiskSource,
    private val authRepository: AuthRepository,
    private val vaultLockManager: VaultLockManager,
    private val dispatcherManager: DispatcherManager,
    private val keySource: WearKeySource,
) : WearConsentManager {
    private val mutex = Mutex()

    override suspend fun getDevices(userId: String): WearConsentResult =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                val result = loadDevices(userId)
                if (result !is WearConsentResult.Success) return@withLock result
                pinAuthorizedKeys(userId, result)
            }
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
            val previous = consents.find { it.userId == userId && it.deviceId == deviceId }
            val decision = WearConsent(
                userId, device.id, device.name, enabled, previous?.publicKey,
            )
            val pinned = resolveKey(decision) ?: return@withLock WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return@withLock WearConsentResult.VaultLocked
            if (!storeDecision(consents, pinned)) return@withLock WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return@withLock WearConsentResult.VaultLocked
            if (enabled) Timber.tag("WearEnrollment").i("Authorized companion key pinned")
            WearConsentResult.Success(
                result.devices.map {
                    if (it.id == deviceId) it.copy(isAuthorized = enabled) else it
                },
            )
        }
    }

    private suspend fun pinAuthorizedKeys(
        userId: String,
        result: WearConsentResult.Success,
    ): WearConsentResult {
        val consents = diskSource.getConsents().getOrNull() ?: return WearConsentResult.Error
        val updated = consents.toMutableList()
        val candidates = consents.withIndex().filter {
            shouldVerifyKey(it.value, userId, result.devices)
        }
        for ((index, consent) in candidates) {
            if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
            val pinned = verifyKey(consent) ?: return WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
            updated[index] = pinned
        }
        if (updated != consents && diskSource.storeConsents(updated).isFailure) {
            return WearConsentResult.Error
        }
        if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
        if (updated != consents) Timber.tag("WearEnrollment").i("Authorized companion key pinned")
        return result
    }

    private fun shouldVerifyKey(
        consent: WearConsent,
        userId: String,
        devices: List<WearConsentDevice>,
    ): Boolean = consent.userId == userId && consent.isAuthorized &&
        devices.any { it.id == consent.deviceId && it.isReachable }

    private fun storeDecision(consents: List<WearConsent>, decision: WearConsent): Boolean =
        diskSource.storeConsents(
            consents.filterNot {
                it.userId == decision.userId && it.deviceId == decision.deviceId
            } + decision,
        ).isSuccess

    private suspend fun resolveKey(consent: WearConsent): WearConsent? =
        if (consent.isAuthorized) verifyKey(consent) else consent

    private suspend fun verifyKey(consent: WearConsent): WearConsent? {
        val key = keySource.getPublicKey(consent.deviceId).getOrNull() ?: return null
        val encoded = Base64.getEncoder().encodeToString(key)
        if (consent.publicKey != null && consent.publicKey != encoded) return null
        return consent.copy(publicKey = encoded)
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
