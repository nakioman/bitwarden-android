package com.x8bit.bitwarden.data.wear

import android.content.Context
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

private const val KEY_TIMEOUT_MILLIS = 15_000L

/** Requests keys over the package/signature-scoped Data Layer RPC transport. */
class WearKeySourceImpl(private val context: Context) : WearKeySource {
    override suspend fun getPublicKey(deviceId: String): Result<ByteArray> {
        if (!WearSyncAvailability.isEnabled || deviceId.isBlank()) {
            return Result.failure(IOException("Companion keys unavailable"))
        }
        val request = WearKeyProtocol.createRequest()
        return withTimeoutOrNull(KEY_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Wearable.getMessageClient(context)
                    .sendRequest(deviceId, WearKeyProtocol.KEY_PATH, request)
                    .addOnSuccessListener { response ->
                        if (continuation.isActive) {
                            continuation.resume(WearKeyProtocol.readResponse(request, response))
                        }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resume(Result.failure(error))
                    }
                    .addOnCanceledListener {
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(IOException("Key request canceled")))
                        }
                    }
            }
        } ?: Result.failure(IOException("Companion key request timed out"))
    }
}
