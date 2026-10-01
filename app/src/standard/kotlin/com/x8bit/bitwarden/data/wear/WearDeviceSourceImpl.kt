package com.x8bit.bitwarden.data.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

private const val COMPANION_CAPABILITY = "bitwarden_wear_companion_v1"
private const val DISCOVERY_TIMEOUT_MILLIS = 10_000L

/** Uses app capabilities so unrelated paired nodes are not offered as companion devices. */
class WearDeviceSourceImpl(private val context: Context) : WearDeviceSource {
    override val isSupported: Boolean = WearSyncAvailability.isEnabled

    override suspend fun getDevices(): Result<List<WearDevice>> =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Wearable.getCapabilityClient(context)
                    .getCapability(COMPANION_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener { capability ->
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.success(
                                    capability.nodes.map { WearDevice(it.id, it.displayName) },
                                ),
                            )
                        }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
            }
        } ?: Result.failure(IOException("Companion discovery timed out"))
}
