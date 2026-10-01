package com.x8bit.bitwarden.wear.debug

import android.util.Log
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "WearConnection"

/** Records a connection response in logcat for the debug-only ADB probe. */
class WearConnectionListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!WearConnectionProtocol.isPong(messageEvent.path, messageEvent.data)) return

        Log.i(TAG, "Watch received connection pong: round trip confirmed")
    }
}
