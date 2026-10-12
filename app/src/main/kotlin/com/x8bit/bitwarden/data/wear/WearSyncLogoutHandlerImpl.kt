package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Depends only on storage and transport so logout does not depend on the auth repository.
 *
 * @property diskSource Per-account sync setting.
 * @property snapshotStore Snapshot removal, serialized with publication.
 * @property scope Process-lifetime scope; removal must outlive the logout call.
 * @property isEnabled Availability of watch sync in this build.
 */
class WearSyncLogoutHandlerImpl(
    private val diskSource: WearSyncDiskSource,
    private val snapshotStore: WearSnapshotStore,
    private val scope: CoroutineScope,
    private val isEnabled: Boolean = WearSyncAvailability.isEnabled,
) : WearSyncLogoutHandler {
    override fun onLogout(userId: String, isHardLogout: Boolean) {
        if (!isEnabled) return
        if (isHardLogout) diskSource.storeSyncEnabled(userId, null)
        scope.launch {
            if (snapshotStore.remove(userId).isFailure) {
                Timber.w("Wear snapshot removal after logout failed")
            }
        }
    }
}
