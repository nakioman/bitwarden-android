package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import java.io.IOException

/** Confirms only targeted commands committed by the production encrypted snapshot manager. */
class WatchDataReceiverImpl(private val snapshotManager: WatchSnapshotManager) : WatchDataReceiver {
    override suspend fun receive(
        localNodeId: String,
        sourceNodeId: String,
        path: String?,
        packet: ByteArray,
    ): Result<ByteArray> {
        if (!WearDataProtocol.isPacketPath(path, localNodeId)) {
            return rejected()
        }
        if (sourceNodeId == localNodeId || sourceNodeId.isBlank() ||
            packet.size > WearSnapshotProtocol.MAX_PACKET_BYTES
        ) {
            return rejected()
        }
        val copy = packet.copyOf()
        return snapshotManager.receive(sourceNodeId, copy).fold(
            onSuccess = {
                WearDataProtocol.createReceipt(copy).fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { rejected() },
                )
            },
            onFailure = { rejected() },
        )
    }
}

private fun rejected(): Result<ByteArray> = Result.failure(IOException("Wear reception rejected"))
