package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Serializes lifecycle commands and repairs revocations from the persisted consent ledger.
 *
 * @property consentSource Durable decisions retained after logout and revocation.
 * @property preparer Account-checked encryption and monotonic revocation preparation.
 * @property outbox Enrollment ownership used to ignore superseded account decisions.
 * @property deliveryManager Authorization-checked ciphertext delivery and receipt processing.
 * @property dispatcherManager Worker dispatcher for lifecycle operations.
 */
class WearSyncCoordinatorImpl(
    private val consentSource: WearConsentDiskSource,
    private val preparer: WearSnapshotPreparer,
    private val outbox: WearOutboxManager,
    private val deliveryManager: WearDeliveryManager,
    private val dispatcherManager: DispatcherManager,
) : WearSyncCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(userId: String): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock { synchronize(userId) }
        }

    override suspend fun retry(): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock { synchronize(null) }
    }

    override suspend fun revokeAccount(userId: String): Result<Unit> =
        withContext(dispatcherManager.io) {
            mutex.withLock {
                val decisions = consentSource.getConsents().getOrElse {
                    return@withLock synchronizationUnavailable()
                }
                val updated = decisions.map {
                    if (it.userId == userId) it.copy(isAuthorized = false) else it
                }
                if (updated != decisions && consentSource.storeConsents(updated).isFailure) {
                    return@withLock synchronizationUnavailable()
                }
                synchronize(null)
            }
        }

    private suspend fun repairRevocation(decision: WearConsent): Result<Unit> {
        val record = outbox.getRecord(decision.deviceId).getOrElse {
            return synchronizationUnavailable()
        } ?: return Result.success(Unit)
        if (record.userId != decision.userId) return Result.success(Unit)
        return preparer.prepareRevocation(decision.userId, decision.deviceId)
    }

    private suspend fun synchronize(refreshUserId: String?): Result<Unit> {
        val decisions = consentSource.getConsents().getOrElse {
            return synchronizationUnavailable()
        }
        var failed = false
        // Persisted withdrawal is the recovery journal if preparation or the process fails.
        for (decision in decisions.filterNot { it.isAuthorized }) {
            if (repairRevocation(decision).isFailure) {
                failed = true
            }
        }
        for (decision in decisions.filter { it.isAuthorized && it.userId == refreshUserId }) {
            if (preparer.prepareSnapshot(decision.userId, decision.deviceId).isFailure) {
                failed = true
            }
        }
        // Independent watches must still receive committed commands after another watch fails.
        if (deliveryManager.deliverPending().isFailure) failed = true
        return if (failed) synchronizationUnavailable() else Result.success(Unit)
    }
}

private fun synchronizationUnavailable(): Result<Unit> =
    Result.failure(IOException("Wear synchronization unavailable"))
