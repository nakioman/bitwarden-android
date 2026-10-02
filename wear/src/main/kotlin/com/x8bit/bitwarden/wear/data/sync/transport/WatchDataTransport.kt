package com.x8bit.bitwarden.wear.data.sync.transport

import com.google.android.gms.wearable.DataItem

/** Bounded companion transport for encrypted snapshots and durable receipts. */
interface WatchDataTransport {
    /** Returns this watch's authenticated Data Layer node identifier. */
    suspend fun getLocalNodeId(): Result<String>

    /** Reads a bounded packet asset while releasing its descriptor on every exit path. */
    suspend fun readPacket(item: DataItem): Result<ByteArray>

    /** Publishes a watch-owned receipt after the caller has durably committed the command. */
    suspend fun publishReceipt(
        localNodeId: String,
        receipt: ByteArray,
    ): Result<Unit>
}
