package com.x8bit.bitwarden.data.wear

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncLifecycleManagerTest {
    private val events = MutableSharedFlow<WearSyncEvent>()
    private val source = mockk<WearSyncEventSource> {
        every { events } returns this@WearSyncLifecycleManagerTest.events
    }
    private val coordinator = mockk<WearSyncCoordinator> {
        coEvery { retry() } returns Result.success(Unit)
        coEvery { refresh(any()) } returns Result.success(Unit)
    }

    @Test
    fun `initialization retries committed commands once then handles lifecycle hints`() = runTest {
        val manager = WearSyncLifecycleManagerImpl(source, coordinator, backgroundScope)
        manager.initialize()
        manager.initialize()
        runCurrent()
        events.emit(WearSyncEvent.Refresh("user"))
        runCurrent()
        events.emit(WearSyncEvent.Retry)
        runCurrent()
        coVerifyOrder {
            coordinator.retry()
            coordinator.refresh("user")
            coordinator.retry()
        }
        coVerify(exactly = 2) { coordinator.retry() }
    }

    @Test
    fun `one unavailable command does not stop later hints`() = runTest {
        coEvery { coordinator.refresh("first") } returns Result.failure(IOException())
        WearSyncLifecycleManagerImpl(source, coordinator, backgroundScope).initialize()
        runCurrent()
        events.emit(WearSyncEvent.Refresh("first"))
        runCurrent()
        events.emit(WearSyncEvent.Refresh("second"))
        runCurrent()
        coVerify(exactly = 1) { coordinator.refresh("second") }
    }

    @Test
    fun `cancellation stops observation rather than converting to a failed command`() = runTest {
        coEvery { coordinator.refresh("first") } throws CancellationException()
        WearSyncLifecycleManagerImpl(source, coordinator, backgroundScope).initialize()
        runCurrent()
        events.emit(WearSyncEvent.Refresh("first"))
        runCurrent()
        events.emit(WearSyncEvent.Refresh("second"))
        runCurrent()
        coVerify(exactly = 0) { coordinator.refresh("second") }
    }
}
