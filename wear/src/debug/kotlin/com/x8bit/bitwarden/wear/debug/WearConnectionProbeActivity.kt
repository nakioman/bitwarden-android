package com.x8bit.bitwarden.wear.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable

private const val TAG = "WearConnection"

/** Sends a debug-only connection check when explicitly launched through ADB. */
class WearConnectionProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        GoogleApiAvailability.getInstance()
            .checkApiAvailability(nodeClient, messageClient)
            .addOnSuccessListener {
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (nodes.isEmpty()) {
                            Log.i(TAG, "No connected phone; pair and connect the watch first")
                            finish()
                        } else {
                            val messages = nodes.map { node ->
                                messageClient.sendMessage(
                                    node.id,
                                    WearConnectionProtocol.PING_PATH,
                                    WearConnectionProtocol.createPayload(),
                                )
                                    .addOnSuccessListener {
                                        Log.i(TAG, "Watch queued connection ping; awaiting pong")
                                    }
                                    .addOnFailureListener {
                                        Log.e(TAG, "Watch failed to send connection ping")
                                    }
                            }
                            Tasks.whenAllComplete(messages).addOnCompleteListener { finish() }
                        }
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Unable to discover connected devices")
                        finish()
                    }
            }
            .addOnFailureListener {
                Log.e(TAG, "Wear Data Layer is unavailable on this device")
                finish()
            }
    }
}
