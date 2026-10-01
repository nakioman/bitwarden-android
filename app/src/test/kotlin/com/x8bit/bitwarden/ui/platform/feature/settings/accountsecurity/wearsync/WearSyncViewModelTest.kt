package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.wear.WearConsentDevice
import com.x8bit.bitwarden.data.wear.WearConsentManager
import com.x8bit.bitwarden.data.wear.WearConsentResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WearSyncViewModelTest : BaseViewModelTest() {
    private val user = mockk<UserState> { every { activeUserId } returns "user" }
    private val users = MutableStateFlow<UserState?>(user)
    private val auth = mockk<AuthRepository> { every { userStateFlow } returns users }
    private val manager = mockk<WearConsentManager>()

    @Test
    fun `selecting and dismissing a device never saves consent`() = runTest {
        coEvery { manager.getDevices("user") } returns WearConsentResult.Success(
            listOf(WearConsentDevice("watch", "Watch", false, true)),
        )
        val viewModel = WearSyncViewModel(manager, auth)
        viewModel.trySendAction(WearSyncAction.Refresh)
        viewModel.trySendAction(WearSyncAction.DeviceClick("watch"))
        assertEquals("watch", viewModel.stateFlow.value.pendingDevice?.id)
        viewModel.trySendAction(WearSyncAction.Dismiss)
        assertNull(viewModel.stateFlow.value.pendingDevice)
        coVerify(exactly = 0) { manager.setConsent(any(), any(), any()) }
    }

    @Test
    fun `confirmation saves consent only for the selected account and device`() = runTest {
        coEvery { manager.getDevices("user") } returns WearConsentResult.Success(
            listOf(WearConsentDevice("watch", "Watch", false, true)),
        )
        coEvery { manager.setConsent("user", "watch", true) } returns WearConsentResult.Success(
            listOf(WearConsentDevice("watch", "Watch", true, true)),
        )
        val viewModel = WearSyncViewModel(manager, auth)
        viewModel.trySendAction(WearSyncAction.Refresh)
        viewModel.trySendAction(WearSyncAction.DeviceClick("watch"))
        viewModel.trySendAction(WearSyncAction.Confirm)
        assertEquals(true, viewModel.stateFlow.value.devices.single().isAuthorized)
        coVerify(exactly = 1) { manager.setConsent("user", "watch", true) }
    }

    @Test
    fun `switching accounts discards the pending decision`() = runTest {
        coEvery { manager.getDevices("user") } returns WearConsentResult.Success(
            listOf(WearConsentDevice("watch", "Watch", false, true)),
        )
        val viewModel = WearSyncViewModel(manager, auth)
        viewModel.trySendAction(WearSyncAction.Refresh)
        viewModel.trySendAction(WearSyncAction.DeviceClick("watch"))
        users.value = null
        viewModel.trySendAction(WearSyncAction.Confirm)
        assertEquals(WearSyncError.VAULT_LOCKED, viewModel.stateFlow.value.error)
        assertNull(viewModel.stateFlow.value.pendingDevice)
        coVerify(exactly = 0) { manager.setConsent(any(), any(), any()) }
    }
}
