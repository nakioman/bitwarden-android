package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * @property eventSource Local changes that may require a watch update.
 * @property syncManager Publishes the active account's codes when allowed.
 * @property scope Process-lifetime worker scope.
 */
class WearSyncLifecycleManagerImpl(
    private val eventSource: WearSyncEventSource,
    private val syncManager: WearSyncManager,
    private val scope: CoroutineScope,
) : WearSyncLifecycleManager {
    private var initialized = false

    @Synchronized
    override fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch(CoroutineExceptionHandler { _, _ ->
            Timber.w("Wear sync observation stopped unexpectedly")
        }) {
            // Bursts of vault changes collapse into a single update.
            eventSource.events.onStart { emit(Unit) }.conflate().collect {
                if (syncManager.sync().isFailure) Timber.d("Wear sync update failed")
            }
        }
    }
}
