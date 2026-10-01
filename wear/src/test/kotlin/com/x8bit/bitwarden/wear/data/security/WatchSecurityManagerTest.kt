package com.x8bit.bitwarden.wear.data.security

import android.app.KeyguardManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchSecurityManagerTest {
    private val keyguardManager = mockk<KeyguardManager>()

    @Test
    fun `missing system service never grants access`() {
        assertEquals(
            WatchSecurityStatus.UNVERIFIED,
            WatchSecurityManagerImpl(keyguardManager = null).getStatus(),
        )
    }

    @Test
    fun `an unlocked watch without a secure screen lock still denies access`() {
        every { keyguardManager.isDeviceSecure } returns false
        assertEquals(
            WatchSecurityStatus.SCREEN_LOCK_REQUIRED,
            WatchSecurityManagerImpl(keyguardManager).getStatus(),
        )
    }

    @Test
    fun `a secure watch requiring authentication denies access`() {
        every { keyguardManager.isDeviceSecure } returns true
        every { keyguardManager.isDeviceLocked } returns true
        assertEquals(
            WatchSecurityStatus.LOCKED,
            WatchSecurityManagerImpl(keyguardManager).getStatus(),
        )
    }

    @Test
    fun `only a secure unlocked watch grants access`() {
        every { keyguardManager.isDeviceSecure } returns true
        every { keyguardManager.isDeviceLocked } returns false
        assertEquals(
            WatchSecurityStatus.UNLOCKED,
            WatchSecurityManagerImpl(keyguardManager).getStatus(),
        )
    }
}
