package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import java.io.IOException

/**
 * Uses events only as hints; the coordinator independently verifies ownership and exact receipts.
 *
 * @property outbox Validated enrollment metadata and pending commands.
 * @property coordinator Withdrawal recovery and authorization-checked retries.
 * @property isEnabled Companion availability for this build.
 */
class WearDeliveryEventManagerImpl(
    private val outbox: WearOutboxManager,
    private val coordinator: WearSyncCoordinator,
    private val isEnabled: Boolean = WearSyncAvailability.isEnabled,
) : WearDeliveryEventManager {
    override suspend fun receiptChanged(
        scheme: String?,
        ownerNodeId: String?,
        path: String?,
    ): Result<Unit> {
        if (!isEnabled || scheme != "wear" || ownerNodeId == null) return Result.success(Unit)
        if (!WearDataProtocol.isReceiptPath(WearDataChannel.PRODUCTION, path, ownerNodeId)) {
            return Result.success(Unit)
        }
        val record = outbox.getRecord(ownerNodeId).getOrElse {
            return Result.failure(IOException("Wear receipt processing unavailable"))
        }
        if (record?.pendingPacket == null) return Result.success(Unit)
        return coordinator.retry()
    }

    override suspend fun capabilityChanged(name: String, hasReachableNodes: Boolean): Result<Unit> {
        if (!isEnabled || name != WearDataProtocol.COMPANION_CAPABILITY || !hasReachableNodes) {
            return Result.success(Unit)
        }
        // Discovery cannot grant permission, repin keys, or extract OTPs while the vault is locked.
        return coordinator.retry()
    }
}
