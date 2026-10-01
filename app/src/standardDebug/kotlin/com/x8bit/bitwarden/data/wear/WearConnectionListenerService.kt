package com.x8bit.bitwarden.data.wear

import android.util.Log
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

private const val TAG = "WearConnection"

/** Replies to debug connection checks without opening or reading the vault. */
class WearConnectionListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!WearConnectionProtocol.isPing(messageEvent.path, messageEvent.data)) return

        Log.i(TAG, "Phone received connection ping")
        Wearable.getMessageClient(this)
            .sendMessage(
                messageEvent.sourceNodeId,
                WearConnectionProtocol.PONG_PATH,
                WearConnectionProtocol.createPayload(),
            )
            .addOnSuccessListener { Log.i(TAG, "Phone queued connection pong") }
            .addOnFailureListener { Log.e(TAG, "Phone failed to send connection pong") }
    }
}
