package com.x8bit.bitwarden.wear.data.sync.transport

import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume

private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/** Google Play transport; assets contain encrypted frames and never plaintext vault data. */
class WatchDataTransportImpl(
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
) : WatchDataTransport {
    override suspend fun getLocalNodeId(): Result<String> = transportResult {
        nodeClient.localNode.awaitTransport().id
    }

    override suspend fun readPacket(item: DataItem): Result<ByteArray> = transportResult {
        val asset = requireNotNull(
            DataMapItem.fromDataItem(item).dataMap.getAsset(WearDataProtocol.PACKET_ASSET),
        )
        val response = dataClient.getFdForAsset(asset).awaitTransport { it.release() }
        try {
            requireNotNull(response.inputStream).use(::readBoundedPacket)
        } finally {
            response.release()
        }
    }

    override suspend fun publishReceipt(
        localNodeId: String,
        receipt: ByteArray,
    ): Result<Unit> = transportResult {
        require(receipt.size == WearDataProtocol.MAX_RECEIPT_BYTES)
        dataClient.putDataItem(
            PutDataRequest.create(WearDataProtocol.receiptPath(localNodeId))
                .setData(receipt.copyOf()).setUrgent(),
        ).awaitTransport()
    }
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

/** Reads a complete frame within the wire size limit; oversized streams fail without truncation. */
internal fun readBoundedPacket(stream: InputStream): ByteArray =
    ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = stream.read(buffer)
        while (count != -1) {
            require(output.size() <= WearSnapshotProtocol.MAX_PACKET_BYTES - count)
            output.write(buffer, 0, count)
            count = stream.read(buffer)
        }
        output.toByteArray()
    }
