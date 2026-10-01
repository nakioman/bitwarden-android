package com.x8bit.bitwarden.wear.data.security

import android.app.KeyguardManager

/** Uses the system screen lock rather than introducing an app-specific PIN. */
class WatchSecurityManagerImpl(
    private val keyguardManager: KeyguardManager?,
) : WatchSecurityManager {
    override fun getStatus(): WatchSecurityStatus = when {
        keyguardManager == null -> WatchSecurityStatus.UNVERIFIED
        !keyguardManager.isDeviceSecure -> WatchSecurityStatus.SCREEN_LOCK_REQUIRED
        keyguardManager.isDeviceLocked -> WatchSecurityStatus.LOCKED
        else -> WatchSecurityStatus.UNLOCKED
    }
}
