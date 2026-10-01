package com.x8bit.bitwarden.wear.data.security

/** Checks whether the watch meets the local access requirements. */
interface WatchSecurityManager {
    /** Returns the current security status without caching an unlock decision. */
    fun getStatus(): WatchSecurityStatus
}

/** Local conditions required before displaying verification codes. */
enum class WatchSecurityStatus {
    /** Access has not been verified for the current foreground session. */
    UNVERIFIED,

    /** No secure screen lock is configured. */
    SCREEN_LOCK_REQUIRED,

    /** The watch requires system authentication before access. */
    LOCKED,

    /** The watch has a secure screen lock and is currently unlocked. */
    UNLOCKED,
}
