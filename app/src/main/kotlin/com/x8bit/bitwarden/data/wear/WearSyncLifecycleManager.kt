package com.x8bit.bitwarden.data.wear

/** Owns phone lifecycle observation independently of settings screens and bound services. */
interface WearSyncLifecycleManager {
    /** Starts observation once for this process and retries previously committed commands. */
    fun initialize()
}
