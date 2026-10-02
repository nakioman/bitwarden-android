package com.x8bit.bitwarden.data.wear

import kotlinx.coroutines.flow.Flow

/** Observes local account, vault, and durable permission changes without exporting secrets. */
interface WearSyncEventSource {
    /** Emits lifecycle hints; each command must revalidate the current account and permissions. */
    val events: Flow<WearSyncEvent>
}

/** A non-sensitive lifecycle hint, never an authorization or a vault payload. */
sealed class WearSyncEvent {
    /** Refresh eligible OTPs for the named account, if still active and unlocked. */
    data class Refresh(val userId: String) : WearSyncEvent()

    /** Retry committed commands and persisted withdrawals without extracting secrets. */
    data object Retry : WearSyncEvent()
}
