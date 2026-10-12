package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.flow.Flow

/** Persists whether each account syncs its verification codes to watches. */
interface WearSyncDiskSource {
    /** Emits after any account's setting changes. */
    val changes: Flow<Unit>

    /** Whether [userId] has turned on watch sync. */
    fun isSyncEnabled(userId: String): Boolean

    /** Stores the setting for [userId]; `null` removes it. */
    fun storeSyncEnabled(userId: String, isEnabled: Boolean?)
}
