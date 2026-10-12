package com.x8bit.bitwarden.data.wear

/** Keeps watches up to date with the active account's verification codes. */
interface WearSyncManager {
    /** Whether [userId] has turned on watch sync. */
    fun isSyncEnabled(userId: String): Boolean

    /**
     * Turns watch sync on or off for [userId]. Turning it off removes that account's codes from
     * every watch; turning it on publishes them as soon as the vault is unlocked.
     */
    suspend fun setSyncEnabled(userId: String, isEnabled: Boolean): Result<Unit>

    /**
     * Publishes the active account's codes to every watch if sync is on and the vault is
     * unlocked. Does nothing otherwise; existing watch codes are kept while the vault is locked.
     */
    suspend fun sync(): Result<Unit>
}
