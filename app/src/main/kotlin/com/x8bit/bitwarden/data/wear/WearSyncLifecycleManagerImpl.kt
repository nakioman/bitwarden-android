package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Processes lifecycle hints sequentially without retaining any decrypted OTP data.
 *
 * @property eventSource Existing account, vault, and durable consent notifications.
 * @property coordinator Authorization-checked preparation, withdrawal repair, and delivery.
 * @property scope Process-lifetime worker scope; independent of settings screen cancellation.
 */
class WearSyncLifecycleManagerImpl(
    private val eventSource: WearSyncEventSource,
    private val coordinator: WearSyncCoordinator,
    private val scope: CoroutineScope,
) : WearSyncLifecycleManager {
    private var initialized = false

    @Synchronized
    override fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch(CoroutineExceptionHandler { _, _ ->
            Timber.w("Wear lifecycle observation stopped unexpectedly")
        }) {
            report(coordinator.retry())
            eventSource.events.collect { event ->
                val result = when (event) {
                    is WearSyncEvent.Refresh -> coordinator.refresh(event.userId)
                    WearSyncEvent.Retry -> coordinator.retry()
                }
                report(result)
            }
        }
    }

    private fun report(result: Result<Unit>) {
        if (result.isFailure) Timber.d("Wear command remains unavailable or pending")
    }
}
