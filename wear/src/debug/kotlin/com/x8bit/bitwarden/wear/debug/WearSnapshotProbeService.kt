package com.x8bit.bitwarden.wear.debug

import android.util.Log
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearConnectionProtocol
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.bitwarden.core.data.wear.WearSnapshotProbeProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.debug.di.SnapshotTransportProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

private const val TAG = "WearSnapshotProbe"

/** Receives synthetic data only; it cannot access production recipient keys or vault caches. */
@AndroidEntryPoint
class WearSnapshotProbeService : WearableListenerService() {
    /** The isolated synthetic recipient. */
    @Inject
    @SnapshotTransportProbe
    lateinit var manager: WatchSnapshotManager

    /** Current system screen lock status. */
    @Inject
    lateinit var securityManager: WatchSecurityManager

    /** Worker dispatcher for bounded disk and crypto operations. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcherManager.io) }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path !in WearSnapshotProbeProtocol.paths) return null
        val completion = TaskCompletionSource<ByteArray>()
        scope.launch {
            handleRequest(nodeId, path, request).fold(
                onSuccess = { completion.trySetResult(it) },
                onFailure = {
                    completion.trySetException(IOException("Synthetic transport probe failed"))
                    Log.i(TAG, "Synthetic transport probe failed")
                },
            )
        }.invokeOnCompletion { error ->
            if (error != null) completion.trySetException(IOException("Probe interrupted"))
        }
        return completion.task
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): Result<ByteArray> {
        if (path == WearSnapshotProbeProtocol.KEY_PATH) {
            if (!WearKeyProtocol.isValidRequest(request)) return rejected()
            return manager.provision().fold(
                onSuccess = { WearKeyProtocol.createResponse(request, it) },
                onFailure = { Result.failure(it) },
            )
        }
        if (path == WearSnapshotProbeProtocol.DELIVERY_PATH) {
            val result = manager.receive(nodeId, request)
            val outcome = if (result.isSuccess) {
                "Synthetic packet accepted"
            } else {
                "Synthetic packet rejected"
            }
            Log.i(TAG, outcome)
            return status(
                if (result.isSuccess) {
                    WearSnapshotProbeProtocol.ACCEPTED
                } else {
                    WearSnapshotProbeProtocol.REJECTED
                },
            )
        }
        if (!request.contentEquals(WearConnectionProtocol.createPayload())) return rejected()
        return when (path) {
            WearSnapshotProbeProtocol.RESET_PATH -> manager.clear().map {
                byteArrayOf(WearSnapshotProbeProtocol.ACCEPTED)
            }
            WearSnapshotProbeProtocol.VERIFY_PATH -> verifySyntheticData()
            else -> rejected()
        }
    }

    private suspend fun verifySyntheticData(): Result<ByteArray> {
        if (securityManager.getStatus() == WatchSecurityStatus.LOCKED) {
            return status(WearSnapshotProbeProtocol.LOCKED)
        }
        return manager.read().map { plaintext ->
            val state = when {
                plaintext == null -> WearSnapshotProbeProtocol.EMPTY
                plaintext.contentEquals(WearSnapshotProbeProtocol.SYNTHETIC_TEXT.toByteArray()) -> {
                    WearSnapshotProbeProtocol.MATCHED
                }
                else -> WearSnapshotProbeProtocol.REJECTED
            }
            plaintext?.fill(0)
            byteArrayOf(state)
        }
    }

    private fun rejected(): Result<ByteArray> = status(WearSnapshotProbeProtocol.REJECTED)

    private fun status(value: Byte): Result<ByteArray> = Result.success(byteArrayOf(value))
}
