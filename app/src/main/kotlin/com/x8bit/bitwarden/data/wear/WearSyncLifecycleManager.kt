package com.x8bit.bitwarden.data.wear

/** Keeps watches updated for the lifetime of the process, independently of any screen. */
interface WearSyncLifecycleManager {
    /** Starts observing changes once for this process. */
    fun initialize()
}
