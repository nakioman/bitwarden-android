package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

/** Wakes pending command processing for receipts and companion reachability in the background. */
@AndroidEntryPoint
class WearDeliveryListenerService : WearableListenerService() {
    /** Revalidates event metadata and delegates exact confirmation to the trusted delivery path. */
    @Inject
    lateinit var eventManager: WearDeliveryEventManager

    /** Background dispatcher for receipt lookup and durable command recovery. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onDataChanged(events: DataEventBuffer) {
        // The callback has a background looper. Complete before the buffer is released and
        // the service unbinds; no asynchronous work retains DataItems from this callback.
        runBlocking(dispatcherManager.io) {
            events.filter { it.type == DataEvent.TYPE_CHANGED }.forEach {
                val uri = it.dataItem.uri
                report(eventManager.receiptChanged(uri.scheme, uri.host, uri.path))
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        runBlocking(dispatcherManager.io) {
            report(eventManager.capabilityChanged(
                capabilityInfo.name, capabilityInfo.nodes.isNotEmpty(),
            ))
        }
    }

    private fun report(result: Result<Unit>) {
        if (result.isFailure) Timber.d("Wear command remains unavailable or pending")
    }
}
