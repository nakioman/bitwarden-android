package com.x8bit.bitwarden.wear.data.sync.transport

import android.net.Uri
import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/** Google Play services transport; snapshots are encrypted and never contain plaintext. */
class WatchDataTransportImpl(
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
) : WatchDataTransport {
    override val snapshotChanges: Flow<Unit> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { trySend(Unit) }
        val uri = Uri.Builder().scheme("wear").path(WearDataProtocol.SNAPSHOT_PATH_PREFIX).build()
        dataClient.addListener(listener, uri, DataClient.FILTER_PREFIX)
        awaitClose { dataClient.removeListener(listener) }
    }

    override suspend fun publishPublicKey(publicKey: ByteArray): Result<Unit> = transportResult {
        dataClient.putDataItem(
            PutDataRequest.create(WearDataProtocol.PUBLIC_KEY_PATH)
                .setData(publicKey.copyOf())
                .setUrgent(),
        ).awaitTransport()
        Unit
    }

    override suspend fun deletePublicKey(): Result<Unit> = transportResult {
        val localNodeId = localNodeId()
        val uri = Uri.Builder().scheme("wear").authority(localNodeId)
            .path(WearDataProtocol.PUBLIC_KEY_PATH).build()
        dataClient.deleteDataItems(uri).awaitTransport()
        Unit
    }

    override suspend fun readSnapshot(): Result<WatchSnapshot?> = transportResult {
        val path = WearDataProtocol.snapshotPath(localNodeId())
        // Omitting the host matches the snapshot whichever phone published it.
        val uri = Uri.Builder().scheme("wear").path(path).build()
        val item = dataClient.getDataItems(uri).awaitTransport { it.release() }.use { items ->
            items.firstOrNull()?.freeze()
        } ?: return@transportResult null
        val map = DataMapItem.fromDataItem(item).dataMap
        val keyId = requireNotNull(map.getString(WearDataProtocol.RECIPIENT_KEY_ID))
        val asset = requireNotNull(map.getAsset(WearDataProtocol.SNAPSHOT_ASSET))
        val response = dataClient.getFdForAsset(asset).awaitTransport { it.release() }
        val envelope = try {
            requireNotNull(response.inputStream).use(::readBoundedSnapshot)
        } finally {
            response.release()
        }
        WatchSnapshot(keyId, envelope)
    }

    private suspend fun localNodeId(): String = nodeClient.localNode.awaitTransport().id
}

private inline fun <T> DataItemBuffer.use(block: (DataItemBuffer) -> T): T = try {
    block(this)
} finally {
    release()
}

private suspend fun <T> transportResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (_: IOException) {
    transportUnavailable()
} catch (_: SecurityException) {
    transportUnavailable()
} catch (_: IllegalArgumentException) {
    transportUnavailable()
} catch (_: IllegalStateException) {
    transportUnavailable()
} catch (_: ClassCastException) {
    transportUnavailable()
}

private fun <T> transportUnavailable(): Result<T> =
    Result.failure(IOException("Wear data unavailable"))

private suspend fun <T> Task<T>.awaitTransport(discard: (T) -> Unit = {}): T =
    withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine<Result<T>> { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(Result.success(result)) { _, _, _ -> discard(result) }
                } else {
                    discard(result)
                }
            }
            addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IOException("Wear data unavailable")))
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IOException("Wear data canceled")))
                }
            }
        }
    }?.getOrThrow() ?: throw IOException("Wear data timed out")

/** Reads a complete snapshot within the size limit; oversized streams fail without truncation. */
internal fun readBoundedSnapshot(stream: InputStream): ByteArray =
    ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = stream.read(buffer)
        while (count != -1) {
            require(output.size() <= WearDataProtocol.MAX_SNAPSHOT_BYTES - count)
            output.write(buffer, 0, count)
            count = stream.read(buffer)
        }
        output.toByteArray()
    }
