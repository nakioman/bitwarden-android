package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

/** Republishes codes when a watch publishes, replaces or removes its key. */
@AndroidEntryPoint
class WearKeyListenerService : WearableListenerService() {
    /** Publishes codes if sync is on and the vault is unlocked. */
    @Inject
    lateinit var syncManager: WearSyncManager

    /** Background dispatcher for the update. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onDataChanged(events: DataEventBuffer) {
        // The manifest filter limits events to watch key items. Finish before the service unbinds.
        runBlocking(dispatcherManager.io) {
            if (syncManager.sync().isFailure) Timber.d("Wear sync update failed")
        }
    }
}
