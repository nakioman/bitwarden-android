package com.x8bit.bitwarden.wear.data.sync

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearKeyProtocol
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Public-key endpoint. It never reads secrets or accepts snapshots. */
@AndroidEntryPoint
class WearKeyListenerService : WearableListenerService() {
    /** Provisioning requires a secure watch screen lock, including while locked. */
    @Inject
    lateinit var storageManager: WatchSnapshotManager

    /** Provides the service worker dispatcher. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcherManager.io) }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WearKeyProtocol.KEY_PATH) return null
        val completion = TaskCompletionSource<ByteArray>()
        if (nodeId.isBlank() || !WearKeyProtocol.isValidRequest(request)) {
            completion.setException(IOException("Invalid public key request"))
            return completion.task
        }
        scope.launch {
            storageManager.provision()
                .fold(
                    onSuccess = { WearKeyProtocol.createResponse(request, it) },
                    onFailure = { Result.failure(it) },
                )
                .fold(
                    onSuccess = {
                        completion.trySetResult(it)
                    },
                    onFailure = {
                        completion.trySetException(IOException("Public key unavailable"))
                    },
                )
        }.invokeOnCompletion { error ->
            if (error != null) completion.trySetException(IOException("Key request interrupted"))
        }
        return completion.task
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
