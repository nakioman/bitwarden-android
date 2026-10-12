package com.x8bit.bitwarden.data.wear

import android.net.Uri
import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

private const val DATA_TIMEOUT_MILLIS = 15_000L

/**
 * Google Play services Data Layer transport. DataItems are persisted and synchronized by the
 * platform, so a snapshot published while the watch is disconnected is delivered on reconnect.
 *
 * @property dataClient Persistent Data Layer storage shared with paired nodes.
 * @property nodeClient Identifies this phone so only its own snapshots are listed or deleted.
 */
class WearDataSourceImpl(
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
) : WearDataSource {
    override suspend fun getWatchKeys(): Result<List<WearWatchKey>> = safeDataCall {
        val localNodeId = localNodeId()
        // Omitting the host matches the key item published by every node.
        val uri = Uri.Builder().scheme("wear").path(WearDataProtocol.PUBLIC_KEY_PATH).build()
        dataClient.getDataItems(uri).awaitData().use { items ->
            items.mapNotNull { it.toWatchKey(localNodeId) }
        }
    }

    override suspend fun getSnapshots(): Result<List<WearSnapshotInfo>> = safeDataCall {
        val uri = Uri.Builder().scheme("wear").authority(localNodeId())
            .path(WearDataProtocol.SNAPSHOT_PATH_PREFIX).build()
        dataClient.getDataItems(uri, DataClient.FILTER_PREFIX).awaitData().use { items ->
            items.mapNotNull { it.toSnapshotInfo() }
        }
    }

    override suspend fun putSnapshot(
        info: WearSnapshotInfo,
        envelope: ByteArray,
    ): Result<Unit> = safeDataCall {
        require(envelope.size <= WearDataProtocol.MAX_SNAPSHOT_BYTES)
        val request = PutDataMapRequest.create(WearDataProtocol.snapshotPath(info.watchNodeId))
            .apply {
                dataMap.putAsset(
                    WearDataProtocol.SNAPSHOT_ASSET, Asset.createFromBytes(envelope.copyOf()),
                )
                dataMap.putString(WearDataProtocol.TARGET_NODE, info.watchNodeId)
                dataMap.putString(WearDataProtocol.OWNER, info.owner)
                dataMap.putString(WearDataProtocol.RECIPIENT_KEY_ID, info.recipientKeyId)
                dataMap.putString(WearDataProtocol.CONTENT_TAG, info.contentTag)
            }
            .asPutDataRequest()
            .setUrgent()
        dataClient.putDataItem(request).awaitData()
        Unit
    }

    override suspend fun deleteSnapshot(watchNodeId: String): Result<Unit> = safeDataCall {
        val uri = Uri.Builder().scheme("wear").authority(localNodeId())
            .path(WearDataProtocol.snapshotPath(watchNodeId)).build()
        dataClient.deleteDataItems(uri).awaitData()
        Unit
    }

    private suspend fun localNodeId(): String = nodeClient.localNode.awaitData().id
}

private fun DataItem.toWatchKey(localNodeId: String): WearWatchKey? {
    val nodeId = uri.host?.takeIf { it.isNotBlank() && it != localNodeId } ?: return null
    val key = data ?: return null
    if (uri.path != WearDataProtocol.PUBLIC_KEY_PATH) return null
    return if (WearDataProtocol.isValidPublicKey(key)) WearWatchKey(nodeId, key.copyOf()) else null
}

private fun DataItem.toSnapshotInfo(): WearSnapshotInfo? {
    val map = DataMapItem.fromDataItem(this).dataMap
    val target = map.getString(WearDataProtocol.TARGET_NODE) ?: return null
    if (uri.path != WearDataProtocol.snapshotPath(target)) return null
    return WearSnapshotInfo(
        watchNodeId = target,
        owner = map.getString(WearDataProtocol.OWNER) ?: return null,
        recipientKeyId = map.getString(WearDataProtocol.RECIPIENT_KEY_ID) ?: return null,
        contentTag = map.getString(WearDataProtocol.CONTENT_TAG) ?: return null,
    )
}

private inline fun <T> DataItemBuffer.use(block: (DataItemBuffer) -> T): T = try {
    block(this)
} finally {
    release()
}

private suspend fun <T> Task<T>.awaitData(): T = withTimeoutOrNull(DATA_TIMEOUT_MILLIS) {
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener {
            if (continuation.isActive) continuation.resume(Result.success(it))
        }
        addOnFailureListener {
            if (continuation.isActive) {
                continuation.resume(Result.failure(IOException("Wear data operation failed")))
            }
        }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.resume(Result.failure(IOException("Wear data operation canceled")))
            }
        }
    }
}?.getOrThrow() ?: throw IOException("Wear data operation timed out")

private suspend inline fun <T> safeDataCall(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (_: IOException) {
    dataUnavailable()
} catch (_: SecurityException) {
    dataUnavailable()
} catch (_: IllegalArgumentException) {
    dataUnavailable()
} catch (_: IllegalStateException) {
    dataUnavailable()
}

private fun <T> dataUnavailable(): Result<T> = Result.failure(IOException("Wear data unavailable"))
