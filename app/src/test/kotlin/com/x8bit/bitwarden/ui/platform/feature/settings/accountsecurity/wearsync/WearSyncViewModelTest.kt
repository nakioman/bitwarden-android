package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.wear.WearDevice
import com.x8bit.bitwarden.data.wear.WearDeviceSource
import com.x8bit.bitwarden.data.wear.WearSyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class WearSyncViewModelTest : BaseViewModelTest() {
    private val user = mockk<UserState> { every { activeUserId } returns "user" }
    private val auth = mockk<AuthRepository> {
        every { userStateFlow } returns MutableStateFlow(user)
    }
    private val syncManager = mockk<WearSyncManager>()
    private val deviceSource = mockk<WearDeviceSource>()

    @BeforeEach
    fun setUp() {
        every { syncManager.isSyncEnabled("user") } returns false
        coEvery { syncManager.setSyncEnabled(any(), any()) } returns Result.success(Unit)
        every { deviceSource.isSupported } returns true
        coEvery { deviceSource.getDevices() } returns Result.success(
            listOf(WearDevice("watch", "Pixel Watch")),
        )
    }

    @Test
    fun `initial state reflects the stored setting`() {
        every { syncManager.isSyncEnabled("user") } returns true
        assertEquals(WearSyncState(isSyncEnabled = true), createViewModel().stateFlow.value)
    }

    @Test
    fun `refresh lists connected watches`() = runTest {
        val viewModel = createViewModel()
        viewModel.trySendAction(WearSyncAction.RefreshClick)
        assertEquals(
            WearSyncState(
                isSyncEnabled = false,
                watches = listOf(WearSyncWatchState("watch", "Pixel Watch")),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `refresh failure shows an error`() = runTest {
        coEvery { deviceSource.getDevices() } returns Result.failure(IOException())
        val viewModel = createViewModel()
        viewModel.trySendAction(WearSyncAction.RefreshClick)
        assertEquals(WearSyncError.LOAD_FAILED, viewModel.stateFlow.value.error)
    }

    @Test
    fun `refresh without support shows unavailable`() = runTest {
        every { deviceSource.isSupported } returns false
        val viewModel = createViewModel()
        viewModel.trySendAction(WearSyncAction.RefreshClick)
        assertEquals(WearSyncError.UNAVAILABLE, viewModel.stateFlow.value.error)
    }

    @Test
    fun `toggling sync applies the setting for the active account`() = runTest {
        val viewModel = createViewModel()
        viewModel.trySendAction(WearSyncAction.SyncToggle(isEnabled = true))
        assertEquals(true, viewModel.stateFlow.value.isSyncEnabled)
        coVerify { syncManager.setSyncEnabled("user", true) }
    }

    @Test
    fun `failing to update watches shows an error`() = runTest {
        coEvery { syncManager.setSyncEnabled(any(), any()) } returns Result.failure(IOException())
        val viewModel = createViewModel()
        viewModel.trySendAction(WearSyncAction.SyncToggle(isEnabled = false))
        assertEquals(WearSyncError.UPDATE_FAILED, viewModel.stateFlow.value.error)
    }

    @Test
    fun `back click navigates back`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(WearSyncAction.BackClick)
            assertEquals(WearSyncEvent.NavigateBack, awaitItem())
        }
    }

    private fun createViewModel() = WearSyncViewModel(
        savedStateHandle = SavedStateHandle(),
        authRepository = auth,
        syncManager = syncManager,
        deviceSource = deviceSource,
    )
}
