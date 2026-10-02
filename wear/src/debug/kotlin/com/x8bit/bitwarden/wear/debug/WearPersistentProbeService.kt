package com.x8bit.bitwarden.wear.debug

import android.util.Log
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.debug.di.SnapshotTransportProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import javax.inject.Inject

private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/** Debug-only persistent receiver; it cannot access production recipient keys or vault caches. */
@AndroidEntryPoint
class WearPersistentProbeService : WearableListenerService() {
    /** Isolated synthetic recipient and durable tombstones. */
    @Inject
    @SnapshotTransportProbe
    lateinit var manager: WatchSnapshotManager

    /** Worker dispatcher for bounded disk and asset operations. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onDataChanged(events: DataEventBuffer) {
        // WearableListenerService invokes this callback on its background looper. Complete before
        // returning because Google Play services releases the buffer and may unbind the service.
        runBlocking(dispatcherManager.io) {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach {
                receive(it.dataItem)
            }
        }
    }

    private suspend fun receive(item: DataItem) {
        try {
            val localNode = Wearable.getNodeClient(this).localNode.awaitProbe()
            if (!WearDataProtocol.isPacketPath(
                    WearDataChannel.SYNTHETIC, item.uri.path, localNode.id,
                )
            ) {
                return
            }
            val sourceNode = requireNotNull(item.uri.host)
            require(sourceNode != localNode.id)
            val client = Wearable.getDataClient(this)
            val packet = readPacket(client, item)
            manager.receive(sourceNode, packet).getOrThrow()
            val receipt = WearDataProtocol.createReceipt(packet).getOrThrow()
            client.putDataItem(
                PutDataRequest.create(
                    WearDataProtocol.receiptPath(WearDataChannel.SYNTHETIC, localNode.id),
                ).setData(receipt).setUrgent(),
            ).awaitProbe()
            Log.i("WearPersistentProbe", "PASS: synthetic packet persisted and receipt published")
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            logFailure()
        } catch (_: SecurityException) {
            logFailure()
        } catch (_: IllegalArgumentException) {
            logFailure()
        } catch (_: IllegalStateException) {
            logFailure()
        } catch (_: ClassCastException) {
            logFailure()
        }
    }

    private suspend fun readPacket(client: DataClient, item: DataItem): ByteArray {
        val asset = requireNotNull(
            DataMapItem.fromDataItem(item).dataMap.getAsset(WearDataProtocol.PACKET_ASSET),
        )
        val response = client.getFdForAsset(asset).awaitProbe { it.release() }
        try {
            return requireNotNull(response.inputStream).use(::readBoundedPacket)
        } finally {
            response.release()
        }
    }

    private fun logFailure() {
        Log.i("WearPersistentProbe", "FAIL: synthetic delivery rejected or unavailable")
    }
}

private suspend fun <T> Task<T>.awaitProbe(discard: (T) -> Unit = {}): T =
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
                    continuation.resume(Result.failure(IOException("Synthetic data unavailable")))
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IOException("Synthetic data canceled")))
                }
            }
        }
    }?.getOrThrow() ?: throw IOException("Synthetic data timed out")

private fun readBoundedPacket(stream: InputStream): ByteArray =
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
