package com.x8bit.bitwarden.data.wear

/** Removes an account's verification codes from watches when it logs out. */
interface WearSyncLogoutHandler {
    /**
     * Removes [userId]'s codes from every watch. A hard logout also forgets the account's sync
     * setting; after a soft logout, sync resumes once the account logs in again.
     */
    fun onLogout(userId: String, isHardLogout: Boolean)
}
