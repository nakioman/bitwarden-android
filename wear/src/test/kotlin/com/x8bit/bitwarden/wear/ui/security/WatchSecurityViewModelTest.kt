package com.x8bit.bitwarden.wear.ui.security

import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchSecurityViewModelTest : BaseViewModelTest() {
    private val securityManager = mockk<WatchSecurityManager>()

    @Test
    fun `a new view model never starts with restored access`() {
        assertEquals(
            WatchSecurityStatus.UNVERIFIED,
            WatchSecurityViewModel(securityManager).stateFlow.value.status,
        )
    }

    @Test
    fun `backgrounding clears access and resuming rechecks removal of screen lock`() = runTest {
        every { securityManager.getStatus() } returns WatchSecurityStatus.UNLOCKED
        val viewModel = WatchSecurityViewModel(securityManager)
        viewModel.stateFlow.test {
            assertEquals(WatchSecurityStatus.UNVERIFIED, awaitItem().status)
            viewModel.trySendAction(WatchSecurityAction.Resume)
            assertEquals(WatchSecurityStatus.UNLOCKED, awaitItem().status)
            viewModel.trySendAction(WatchSecurityAction.Pause)
            assertEquals(WatchSecurityStatus.UNVERIFIED, awaitItem().status)
            every { securityManager.getStatus() } returns WatchSecurityStatus.SCREEN_LOCK_REQUIRED
            viewModel.trySendAction(WatchSecurityAction.Resume)
            assertEquals(WatchSecurityStatus.SCREEN_LOCK_REQUIRED, awaitItem().status)
        }
    }

    @Test
    fun `opening settings does not grant access`() = runTest {
        val viewModel = WatchSecurityViewModel(securityManager)
        viewModel.eventFlow.test {
            viewModel.trySendAction(WatchSecurityAction.OpenSettings)
            assertEquals(WatchSecurityEvent.OpenSettings, awaitItem())
            assertEquals(WatchSecurityStatus.UNVERIFIED, viewModel.stateFlow.value.status)
        }
    }
}
