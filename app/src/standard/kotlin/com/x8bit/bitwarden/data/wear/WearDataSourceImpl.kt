package com.x8bit.bitwarden.data.wear

import android.net.Uri
import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

private const val DATA_TIMEOUT_MILLIS = 15_000L

/**
 * Publishes bounded ciphertext assets; receipt lookups are scoped to the watch-owned URI.
 *
 * @property dataClient Google Play services persistent transport.
 * @property uuidManager Unique attempt identifiers for safe redelivery of unchanged packets.
 */
class WearDataSourceImpl(
    private val dataClient: DataClient,
    private val uuidManager: UuidManager,
) : WearDataSource {
    override suspend fun publish(deviceId: String, packet: ByteArray): Result<Unit> = safeDataCall {
        check(WearSyncAvailability.isEnabled)
        require(WearSnapshotProtocol.decode(packet).isSuccess)
        val path = WearDataProtocol.packetPath(deviceId)
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putAsset(WearDataProtocol.PACKET_ASSET, Asset.createFromBytes(packet.copyOf()))
            dataMap.putString(WearDataProtocol.ATTEMPT, uuidManager.generateUuid())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).awaitData().getOrThrow()
        Unit
    }

    override suspend fun getReceipt(deviceId: String): Result<ByteArray?> = safeDataCall {
        check(WearSyncAvailability.isEnabled)
        val uri = Uri.Builder().scheme("wear").authority(deviceId)
            .path(WearDataProtocol.receiptPath(deviceId)).build()
        val item = dataClient.getDataItem(uri).awaitData().getOrThrow()
        require(item == null || item.uri == uri)
        val receipt = item?.data
        require(receipt == null || receipt.size <= WearDataProtocol.MAX_RECEIPT_BYTES)
        receipt?.copyOf()
    }
}

private suspend fun <T> Task<T>.awaitData(): Result<T> = withTimeoutOrNull(DATA_TIMEOUT_MILLIS) {
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
} ?: Result.failure(IOException("Wear data operation timed out"))

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
