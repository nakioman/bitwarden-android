package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.flow.Flow

/** Signals local changes that may require watches to be updated. */
interface WearSyncEventSource {
    /** Emits a hint whenever watches may need an update; it carries no account data. */
    val events: Flow<Unit>
}
