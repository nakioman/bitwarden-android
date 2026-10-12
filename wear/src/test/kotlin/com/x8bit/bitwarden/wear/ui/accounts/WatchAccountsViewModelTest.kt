package com.x8bit.bitwarden.wear.ui.accounts

import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.wear.data.otp.WatchOtpCode
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCodeSource
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WatchAccountsViewModelTest : BaseViewModelTest() {
    private var now = 59L
    private val clock = mockk<Clock> { every { instant() } answers { Instant.ofEpochSecond(now) } }
    private val security = mockk<WatchSecurityManager> {
        every { getStatus() } returns WatchSecurityStatus.UNLOCKED
    }
    private val snapshotChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val snapshots = mockk<WatchSnapshotManager> {
        every { this@mockk.changes } returns snapshotChanges
        coEvery { provision() } returns Result.success(Unit)
    }
    private val github = account("github", "GitHub")
    private val google = account("google", "Google")
    private val source = mockk<WatchVerificationCodeSource> {
        coEvery { read() } answers { Result.success(listOf(google, github)) }
    }

    @Test
    fun `new view model has no restored account data or access`() {
        assertEquals(WatchAccountsState(), createViewModel().stateFlow.value)
    }

    @Test
    fun `list uses the same name comparator as the phone`() = runTest {
        val entries = listOf(
            account("upper", "Alpha"), account("z", "Zulu"),
            account("lower", "alpha"), account("special", "!Name"),
        )
        coEvery { source.read() } returns Result.success(entries)
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        assertEquals(
            listOf("special", "lower", "upper", "z"),
            vm.stateFlow.value.accounts.map { it.id },
        )
        assertEquals(WatchAccountsViewState.CONTENT, vm.stateFlow.value.viewState)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `selection and favorites remain associated with ID after a name changes order`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Select("google"))
        vm.trySendAction(WatchAccountsAction.ToggleFavorite("google"))
        val renamed = google.copy(name = "A Google")
        coEvery { source.read() } returns Result.success(listOf(github, renamed))
        snapshotChanges.emit(Unit)
        runCurrent()
        assertEquals("google", vm.stateFlow.value.selectedId)
        assertEquals(setOf("google"), vm.stateFlow.value.favoriteIds)
        assertEquals(listOf(renamed, github), vm.stateFlow.value.accounts)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `double tap can remove an existing favorite and back clears selection`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Select("github"))
        vm.trySendAction(WatchAccountsAction.ToggleFavorite("github"))
        vm.trySendAction(WatchAccountsAction.ToggleFavorite("github"))
        vm.trySendAction(WatchAccountsAction.Back)
        runCurrent()
        assertEquals(emptySet<String>(), vm.stateFlow.value.favoriteIds)
        assertNull(vm.stateFlow.value.selectedId)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `revocation empties the visible list selection and favorite markers`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Select("github"))
        vm.trySendAction(WatchAccountsAction.ToggleFavorite("github"))
        coEvery { source.read() } returns Result.success(emptyList())
        snapshotChanges.emit(Unit)
        runCurrent()
        assertEquals(WatchAccountsViewState.EMPTY, vm.stateFlow.value.viewState)
        assertEquals(emptyList<WatchVerificationCode>(), vm.stateFlow.value.accounts)
        assertNull(vm.stateFlow.value.selectedId)
        assertEquals(emptySet<String>(), vm.stateFlow.value.favoriteIds)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `tick regenerates at actual period boundary and hides expired codes`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        now = 60
        val fresh = account("github", "GitHub", time = now)
        coEvery { source.read() } returns Result.success(listOf(fresh))
        assertNull(github.validOtpAt(now))
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(fresh), vm.stateFlow.value.accounts)
        coVerify(exactly = 2) { source.read() }
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `ticks within the period do not decrypt or regenerate again`() = runTest {
        now = 31
        coEvery { source.read() } returns
            Result.success(listOf(account("github", "GitHub", time = now)))
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        now = 32
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(now, vm.stateFlow.value.nowSeconds)
        coVerify(exactly = 1) { source.read() }
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `clock rollback invalidates old code even within the same period`() {
        assertNull(github.validOtpAt(58))
        assertEquals(github.otp, github.validOtpAt(59))
        val longPeriod = github.copy(otp = WatchOtpCode("12345678", 60))
        assertNull(longPeriod.validOtpAt(60))
    }

    @Test
    fun `backgrounding clears codes and stops reads until resume`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Pause)
        runCurrent()
        assertEquals(WatchAccountsState(), vm.stateFlow.value)
        snapshotChanges.emit(Unit)
        advanceTimeBy(1000)
        runCurrent()
        coVerify(exactly = 1) { source.read() }
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        coVerify(exactly = 2) { source.read() }
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `locking while foreground discards data and unlocking rereads`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        every { security.getStatus() } returns WatchSecurityStatus.LOCKED
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(emptyList<WatchVerificationCode>(), vm.stateFlow.value.accounts)
        assertEquals(WatchSecurityStatus.LOCKED, vm.stateFlow.value.status)
        every { security.getStatus() } returns WatchSecurityStatus.UNLOCKED
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(WatchAccountsViewState.CONTENT, vm.stateFlow.value.viewState)
        coVerify(exactly = 2) { source.read() }
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `failed refresh clears stale codes and retry rereads protected data`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        coEvery { source.read() } returns Result.failure(IOException())
        snapshotChanges.emit(Unit)
        runCurrent()
        assertEquals(WatchAccountsViewState.ERROR, vm.stateFlow.value.viewState)
        assertEquals(emptyList<WatchVerificationCode>(), vm.stateFlow.value.accounts)
        coEvery { source.read() } returns Result.success(listOf(github))
        vm.trySendAction(WatchAccountsAction.Retry)
        runCurrent()
        assertEquals(listOf(github), vm.stateFlow.value.accounts)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    @Test
    fun `result queued from a paused session cannot repopulate accounts`() = runTest {
        val pending = CompletableDeferred<Result<List<WatchVerificationCode>>>()
        coEvery { source.read() } coAnswers { pending.await() }
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Pause)
        pending.complete(Result.success(listOf(github)))
        vm.trySendAction(WatchAccountsAction.Internal.Loaded(1, Result.success(listOf(github))))
        runCurrent()
        assertEquals(WatchAccountsState(), vm.stateFlow.value)
    }

    @Test
    fun `unknown account actions do not create a favorite or selection`() = runTest {
        val vm = createViewModel()
        vm.trySendAction(WatchAccountsAction.Resume)
        runCurrent()
        vm.trySendAction(WatchAccountsAction.Select("unknown"))
        vm.trySendAction(WatchAccountsAction.ToggleFavorite("unknown"))
        runCurrent()
        assertNull(vm.stateFlow.value.selectedId)
        assertFalse("unknown" in vm.stateFlow.value.favoriteIds)
        vm.trySendAction(WatchAccountsAction.Pause)
    }

    private fun createViewModel() = WatchAccountsViewModel(source, snapshots, security, clock)

    private fun account(id: String, name: String, time: Long = 59) = WatchVerificationCode(
        id, name, "user@example.com", null, WatchOtpCode("123456", 30), Instant.ofEpochSecond(time),
    )
}
