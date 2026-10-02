package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Base64

/**
 * Guards explicit consent and retains revocations for later synchronization.
 *
 * @property deviceSource Reachable companion discovery.
 * @property diskSource Atomic account-scoped permission ledger.
 * @property accountSource Active logged-in unlocked account guard.
 * @property dispatcherManager Worker dispatcher for key and disk operations.
 * @property keySource Authenticated recipient key exchange.
 * @property outbox Durable enrollment and exact revocation acknowledgment metadata.
 */
class WearConsentManagerImpl(
    private val deviceSource: WearDeviceSource,
    private val diskSource: WearConsentDiskSource,
    private val accountSource: WearOtpAccountSource,
    private val dispatcherManager: DispatcherManager,
    private val keySource: WearKeySource,
    private val outbox: WearOutboxManager,
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
            val pinned = resolveKey(decision, previous) ?: return@withLock WearConsentResult.Error
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
        val candidates = consents.withIndex().filter { (_, consent) ->
            consent.userId == userId && consent.isAuthorized &&
                result.devices.any { it.id == consent.deviceId && it.isReachable }
        }
        for ((index, consent) in candidates) {
            if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
            val pinned = verifyKey(consent) ?: return WearConsentResult.Error
            if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
            updated[index] = pinned
        }
        if (updated != consents && diskSource.compareAndSetConsents(consents, updated).isFailure) {
            return WearConsentResult.Error
        }
        if (!isAccountUnlocked(userId)) return WearConsentResult.VaultLocked
        if (updated != consents) Timber.tag("WearEnrollment").i("Authorized companion key pinned")
        return result
    }

    private fun storeDecision(consents: List<WearConsent>, decision: WearConsent): Boolean =
        diskSource.compareAndSetConsents(
            consents,
            consents.filterNot {
                it.userId == decision.userId && it.deviceId == decision.deviceId
            } + decision,
        ).isSuccess

    private suspend fun resolveKey(consent: WearConsent, previous: WearConsent?): WearConsent? {
        if (!consent.isAuthorized) return consent
        val record = outbox.getRecord(consent.deviceId).getOrElse { return null }
        if (!canAuthorize(record, previous, consent.userId)) return null
        val key = keySource.getPublicKey(consent.deviceId).getOrNull() ?: return null
        val encoded = Base64.getEncoder().encodeToString(key)
        val keyId = WearSnapshotProtocol.recipientKeyId(key)
        if (!matchesEnrollmentKey(record, keyId)) return null
        // Only an explicit enable after withdrawal can replace a pin. Refresh stays strict.
        val canReplacePin = previous?.isAuthorized != true &&
            (record?.lastHeader == null || record.lastHeader.isRevocation)
        if (consent.publicKey != null && consent.publicKey != encoded && !canReplacePin) return null
        if (outbox.getRecord(consent.deviceId).getOrElse { return null } != record) return null
        return consent.copy(publicKey = encoded)
    }

    private fun canAuthorize(
        record: WearOutboxRecord?,
        previous: WearConsent?,
        userId: String,
    ): Boolean {
        val header = record?.lastHeader ?: return true
        return if (header.isRevocation) {
            record.pendingPacket == null
        } else {
            record.userId == userId && previous?.isAuthorized != false
        }
    }

    private fun matchesEnrollmentKey(record: WearOutboxRecord?, keyId: String): Boolean {
        val header = record?.lastHeader ?: return true
        return if (header.isRevocation) {
            header.recipientKeyId != keyId
        } else {
            header.recipientKeyId == keyId
        }
    }

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
        accountSource.getAccount(userId) != null
}
