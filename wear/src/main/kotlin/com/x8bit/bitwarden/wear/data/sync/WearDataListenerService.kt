package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataListenerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Receives encrypted companion commands without opening the UI or decrypting OTP data. */
@AndroidEntryPoint
class WearDataListenerService : WearableListenerService() {
    /** Validates targets, persists commands, and publishes receipts. */
    @Inject
    lateinit var manager: WatchDataListenerManager

    /** Worker dispatcher for bounded transport and persistence. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onDataChanged(events: DataEventBuffer) {
        // This callback runs on a background looper. Finish before GMS releases its buffer
        // and unbinds the service; a detached coroutine can lose a committed receipt.
        runBlocking(dispatcherManager.io) {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach {
                manager.receive(it.dataItem)
            }
        }
    }
}
