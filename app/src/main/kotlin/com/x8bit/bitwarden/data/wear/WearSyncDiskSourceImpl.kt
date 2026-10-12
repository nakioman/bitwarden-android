package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onSubscription

private const val SYNC_ENABLED_KEY_PREFIX = "wearSyncEnabled_"

/** Stores the non-sensitive per-account setting in unencrypted preferences. */
class WearSyncDiskSourceImpl(
    private val sharedPreferences: SharedPreferences,
) : WearSyncDiskSource {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = mutableChanges.asSharedFlow()
        .onSubscription { emit(Unit) }

    override fun isSyncEnabled(userId: String): Boolean =
        sharedPreferences.getBoolean(SYNC_ENABLED_KEY_PREFIX + userId, false)

    override fun storeSyncEnabled(userId: String, isEnabled: Boolean?) {
        sharedPreferences.edit {
            if (isEnabled == null) {
                remove(SYNC_ENABLED_KEY_PREFIX + userId)
            } else {
                putBoolean(SYNC_ENABLED_KEY_PREFIX + userId, isEnabled)
            }
        }
        mutableChanges.tryEmit(Unit)
    }
}
