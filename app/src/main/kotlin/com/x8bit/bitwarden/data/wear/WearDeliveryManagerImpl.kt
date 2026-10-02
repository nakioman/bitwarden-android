package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

private const val MAX_ENCODED_PUBLIC_KEY_LENGTH = 2048

/**
 * Serializes retries and rechecks authorization around transport awaits and receipt processing.
 *
 * @property outbox Durable encrypted commands and exact acknowledgment rules.
 * @property consentSource Current account-scoped consent and pinned public recipient keys.
 * @property authRepository Logged-in active account identity; vault unlock is not required here.
 * @property dataSource Persistent transport and watch-owned receipt lookup.
 * @property dispatcherManager Worker dispatcher for disk and transport operations.
 */
class WearDeliveryManagerImpl(
    private val outbox: WearOutboxManager,
    private val consentSource: WearConsentDiskSource,
    private val authRepository: AuthRepository,
    private val dataSource: WearDataSource,
    private val dispatcherManager: DispatcherManager,
) : WearDeliveryManager {
    private val mutex = Mutex()

    override suspend fun deliverPending(): Result<Unit> = withContext(dispatcherManager.io) {
        mutex.withLock {
            deliveryResult {
                var failed = false
                for (pending in outbox.getPending().getOrThrow()) {
                    if (deliver(pending).isFailure) failed = true
                }
                if (failed) throw IOException("Pending Wear delivery failed")
            }
        }
    }

    private suspend fun deliver(pending: WearPendingPacket): Result<Unit> = deliveryResult {
        val frame = WearSnapshotProtocol.decode(pending.packet).getOrThrow()
        if (!isAllowed(pending, frame)) return@deliveryResult
        if (acknowledgeIfReceived(pending, frame)) return@deliveryResult
        if (!isAllowed(pending, frame)) return@deliveryResult
        val current = outbox.getRecord(pending.deviceId).getOrThrow()
        if (current?.lastDigest != packetDigest(pending.packet) || current.pendingPacket == null) {
            return@deliveryResult
        }
        // Recheck after the outbox read too: it can suspend while another operation commits.
        if (!isAllowed(pending, frame)) return@deliveryResult
        dataSource.publish(pending.deviceId, pending.packet).getOrThrow()
    }

    private suspend fun acknowledgeIfReceived(
        pending: WearPendingPacket,
        frame: WearSnapshotFrame,
    ): Boolean {
        val receipt = dataSource.getReceipt(pending.deviceId).getOrThrow() ?: return false
        if (!WearDataProtocol.matchesReceipt(receipt, pending.packet)) return false
        if (!isAllowed(pending, frame)) return false
        outbox.acknowledge(pending.deviceId, pending.packet).getOrThrow()
        return true
    }

    private fun isAllowed(pending: WearPendingPacket, frame: WearSnapshotFrame): Boolean {
        // Revocations originate from the trusted preparer and must survive account removal.
        if (frame.header.isRevocation) return true
        val user = authRepository.userStateFlow.value ?: return false
        if (user.activeUserId != pending.userId || !user.activeAccount.isLoggedIn) return false
        val consent = consentSource.getConsents().getOrThrow().singleOrNull {
            it.userId == pending.userId && it.deviceId == pending.deviceId && it.isAuthorized
        } ?: return false
        val key = consent.publicKey ?: return false
        require(key.length <= MAX_ENCODED_PUBLIC_KEY_LENGTH)
        return WearSnapshotProtocol.recipientKeyId(Base64.getDecoder().decode(key)) ==
            frame.header.recipientKeyId
    }
}

private suspend fun deliveryResult(block: suspend () -> Unit): Result<Unit> = try {
    block()
    Result.success(Unit)
} catch (error: CancellationException) {
    throw error
} catch (_: IOException) {
    unavailable()
} catch (_: SecurityException) {
    unavailable()
} catch (_: IllegalArgumentException) {
    unavailable()
} catch (_: IllegalStateException) {
    unavailable()
} catch (_: NoSuchElementException) {
    unavailable()
}

private fun unavailable(): Result<Unit> = Result.failure(IOException("Wear delivery unavailable"))
