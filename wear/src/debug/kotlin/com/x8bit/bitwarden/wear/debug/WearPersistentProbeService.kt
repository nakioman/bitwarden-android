package com.x8bit.bitwarden.wear.debug

import android.util.Log
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.WearableListenerService
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.debug.data.WatchDataTransport
import com.x8bit.bitwarden.wear.debug.di.SnapshotTransportProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject

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

    /** Bounded asset access shared with the production listener. */
    @Inject
    lateinit var transport: WatchDataTransport

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
            val localNodeId = transport.getLocalNodeId().getOrThrow()
            if (!WearDataProtocol.isPacketPath(
                    WearDataChannel.SYNTHETIC, item.uri.path, localNodeId,
                )
            ) {
                return
            }
            val sourceNode = requireNotNull(item.uri.host)
            require(sourceNode != localNodeId)
            val packet = transport.readPacket(item).getOrThrow()
            manager.receive(sourceNode, packet).getOrThrow()
            val receipt = WearDataProtocol.createReceipt(packet).getOrThrow()
            transport.publishReceipt(
                WearDataChannel.SYNTHETIC, localNodeId, receipt,
            ).getOrThrow()
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

    private fun logFailure() {
        Log.i("WearPersistentProbe", "FAIL: synthetic delivery rejected or unavailable")
    }
}
