package com.x8bit.bitwarden.wear.debug.data

import com.bitwarden.core.data.wear.WearDataChannel
import com.google.android.gms.wearable.DataItem

/** Bounded transport shared by the companion listener and isolated synthetic probes. */
interface WatchDataTransport {
    /** Returns this watch's authenticated Data Layer node identifier. */
    suspend fun getLocalNodeId(): Result<String>

    /** Reads a bounded packet asset while releasing its descriptor on every exit path. */
    suspend fun readPacket(item: DataItem): Result<ByteArray>

    /** Publishes a watch-owned receipt after the caller has durably committed the command. */
    suspend fun publishReceipt(
        channel: WearDataChannel,
        localNodeId: String,
        receipt: ByteArray,
    ): Result<Unit>
}
