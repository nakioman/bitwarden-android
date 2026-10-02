package com.x8bit.bitwarden.wear.data.sync.transport

import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.wearable.DataItem
import com.x8bit.bitwarden.wear.data.sync.WatchDataReceiver
import java.io.IOException

/** Filters authenticated DataItem ownership and target before asset download or persistence. */
class WatchDataListenerManagerImpl(
    private val transport: WatchDataTransport,
    private val receiver: WatchDataReceiver,
) : WatchDataListenerManager {
    override suspend fun receive(item: DataItem): Result<Unit> {
        val localNodeId = transport.getLocalNodeId().getOrElse { return rejected() }
        val uri = item.uri
        val sourceNodeId = uri.host ?: return rejected()
        val path = uri.path
        if (uri.scheme != "wear" || sourceNodeId == localNodeId || sourceNodeId.isBlank()) {
            return rejected()
        }
        if (!WearDataProtocol.isPacketPath(WearDataChannel.PRODUCTION, path, localNodeId)) {
            return rejected()
        }
        val packet = transport.readPacket(item).getOrElse { return rejected() }
        val receipt = receiver.receive(localNodeId, sourceNodeId, path, packet)
            .getOrElse { return rejected() }
        return transport.publishReceipt(WearDataChannel.PRODUCTION, localNodeId, receipt)
    }
}

private fun rejected(): Result<Unit> = Result.failure(IOException("Wear reception unavailable"))
