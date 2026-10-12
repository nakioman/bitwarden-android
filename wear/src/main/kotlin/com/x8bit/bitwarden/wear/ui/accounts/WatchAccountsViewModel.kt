package com.x8bit.bitwarden.wear.ui.accounts

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import com.bitwarden.core.data.repository.util.SpecialCharWithPrecedenceComparator
import com.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.wear.data.otp.WatchOtpCode
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCode
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCodeSource
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.time.Clock
import javax.inject.Inject

private const val TICK_MILLIS = 1000L

/**
 * Holds codes only during a foreground session, never saving them to SavedStateHandle.
 *
 * @property source Protected, seed-free account reads.
 * @property snapshots Key publication and snapshot change signals.
 * @property security Uncached local access checks.
 * @property clock Time for expiry and the visible countdown.
 */
@HiltViewModel
class WatchAccountsViewModel @Inject constructor(
    private val source: WatchVerificationCodeSource,
    private val snapshots: WatchSnapshotManager,
    private val security: WatchSecurityManager,
    private val clock: Clock,
) : BaseViewModel<WatchAccountsState, Unit, WatchAccountsAction>(WatchAccountsState()) {
    private var foreground = false
    private var generation = 0L
    private var sessionJob: Job? = null
    private var readJob: Job? = null

    override fun handleAction(action: WatchAccountsAction) {
        when (action) {
            WatchAccountsAction.Resume -> handleResume()
            WatchAccountsAction.Pause -> handlePause()
            WatchAccountsAction.Retry -> if (foreground) refresh()
            WatchAccountsAction.Back -> mutableStateFlow.update { it.copy(selectedId = null) }
            is WatchAccountsAction.Select -> mutableStateFlow.update {
                it.copy(selectedId = action.id.takeIf { id -> it.accounts.any { a -> a.id == id } })
            }
            is WatchAccountsAction.ToggleFavorite -> handleFavorite(action.id)
            is WatchAccountsAction.Internal.Tick -> handleTick(action.session)
            is WatchAccountsAction.Internal.Changed -> {
                if (foreground && action.session == sessionJob) refresh(invalidate = true)
            }
            is WatchAccountsAction.Internal.Loaded -> handleLoaded(action)
        }
    }

    private fun handleResume() {
        if (foreground) return
        foreground = true
        sessionJob = viewModelScope.launch {
            val session = coroutineContext[Job]
            // Republishing the key on every visit lets the phone recover if it was ever lost.
            launch { snapshots.provision() }
            launch {
                snapshots.changes.collect {
                    sendAction(WatchAccountsAction.Internal.Changed(session))
                }
            }
            while (true) {
                delay(TICK_MILLIS)
                sendAction(WatchAccountsAction.Internal.Tick(session))
            }
        }
        refresh(invalidate = true)
    }

    private fun handlePause() {
        foreground = false
        generation++
        sessionJob?.cancel()
        readJob?.cancel()
        sessionJob = null
        mutableStateFlow.update {
            WatchAccountsState(favoriteIds = it.favoriteIds)
        }
    }

    private fun handleTick(session: Job?) {
        if (!foreground || session != sessionJob) return
        val status = security.getStatus()
        val now = clock.instant().epochSecond
        val needsCodes = state.viewState == WatchAccountsViewState.LOADING ||
            state.accounts.any { it.otp != null && it.validOtpAt(now) == null }
        mutableStateFlow.update { it.copy(status = status, nowSeconds = now) }
        if (status != WatchSecurityStatus.UNLOCKED) {
            generation++
            readJob?.cancel()
            mutableStateFlow.update {
                it.copy(
                    accounts = emptyList(),
                    selectedId = null,
                    viewState = WatchAccountsViewState.LOADING,
                )
            }
        } else if (readJob?.isActive != true && needsCodes) {
            refresh()
        }
    }

    private fun refresh(invalidate: Boolean = false) {
        val status = security.getStatus()
        generation++
        val request = generation
        readJob?.cancel()
        mutableStateFlow.update {
            it.copy(
                status = status,
                accounts = if (invalidate || status != WatchSecurityStatus.UNLOCKED) {
                    emptyList()
                } else {
                    it.accounts
                },
                viewState = if (invalidate || it.accounts.isEmpty()) {
                    WatchAccountsViewState.LOADING
                } else {
                    it.viewState
                },
                nowSeconds = clock.instant().epochSecond,
            )
        }
        if (status != WatchSecurityStatus.UNLOCKED) return
        readJob = viewModelScope.launch {
            sendAction(WatchAccountsAction.Internal.Loaded(request, source.read()))
        }
    }

    private fun handleLoaded(action: WatchAccountsAction.Internal.Loaded) {
        if (!foreground || action.generation != generation) return
        val status = security.getStatus()
        val accounts = if (status == WatchSecurityStatus.UNLOCKED) {
            action.result.getOrNull().orEmpty().sortedWith { first, second ->
                SpecialCharWithPrecedenceComparator.compare(first.name, second.name)
            }
        } else {
            emptyList()
        }
        mutableStateFlow.update {
            it.copy(
                status = status,
                accounts = accounts,
                selectedId = it.selectedId.takeIf { id ->
                    accounts.any { account -> account.id == id }
                },
                favoriteIds = if (action.result.isSuccess) {
                    it.favoriteIds.intersect(accounts.map { a -> a.id }.toSet())
                } else {
                    it.favoriteIds
                },
                nowSeconds = clock.instant().epochSecond,
                viewState = when {
                    action.result.isFailure -> WatchAccountsViewState.ERROR
                    accounts.isEmpty() -> WatchAccountsViewState.EMPTY
                    else -> WatchAccountsViewState.CONTENT
                },
            )
        }
    }

    private fun handleFavorite(id: String) {
        if (state.accounts.none { it.id == id }) return
        mutableStateFlow.update {
            it.copy(
                favoriteIds = if (id in it.favoriteIds) {
                    it.favoriteIds - id
                } else {
                    it.favoriteIds + id
                },
            )
        }
    }
}

/**
 * Ephemeral foreground data; sensitive account state is excluded from parcel serialization.
 *
 * @property status Latest uncached system security status.
 * @property viewState Current rendering phase.
 * @property nowSeconds Time used to display the countdown and suppress expired codes.
 * @property accounts Seed-free accounts, never serialized or saved to a Bundle.
 * @property selectedId Currently displayed account, scoped to this foreground session.
 * @property favoriteIds In-memory favorite markers; persistence is deferred to the tiles feature.
 */
@Parcelize
data class WatchAccountsState(
    val status: WatchSecurityStatus = WatchSecurityStatus.UNVERIFIED,
    val viewState: WatchAccountsViewState = WatchAccountsViewState.LOADING,
    val nowSeconds: Long = 0,
    @IgnoredOnParcel val accounts: List<WatchVerificationCode> = emptyList(),
    @IgnoredOnParcel val selectedId: String? = null,
    @IgnoredOnParcel val favoriteIds: Set<String> = emptySet(),
) : Parcelable

/** Non-sensitive rendering phase. */
enum class WatchAccountsViewState {
    /** A protected read is pending. */
    LOADING,
    /** The protected read contains accounts. */
    CONTENT,
    /** No local accounts remain. */
    EMPTY,
    /** Reading failed; stale codes are discarded. */
    ERROR,
}

/** UI and lifecycle actions; none contain OTP seeds. */
sealed class WatchAccountsAction {
    /** Begin a fresh foreground session. */
    data object Resume : WatchAccountsAction()
    /** Cancel work and erase presentation data. */
    data object Pause : WatchAccountsAction()
    /** Retry a failed protected read. */
    data object Retry : WatchAccountsAction()
    /** Return to the account list. */
    data object Back : WatchAccountsAction()
    /** Select an account by stable vault ID. */
    data class Select(val id: String) : WatchAccountsAction()
    /** Toggle the account's local favorite marker. */
    data class ToggleFavorite(val id: String) : WatchAccountsAction()
    /** Results from work scoped to the current foreground session. */
    sealed class Internal : WatchAccountsAction() {
        /** Recheck time and local access. */
        data class Tick(val session: Job?) : Internal()
        /** Invalidate codes after a committed snapshot change. */
        data class Changed(val session: Job?) : Internal()
        /** Finish a versioned protected read. */
        data class Loaded(
            val generation: Long,
            val result: Result<List<WatchVerificationCode>>,
        ) : Internal()
    }
}

/** Returns a code only within its original SDK period, including after a clock adjustment. */
internal fun WatchVerificationCode.validOtpAt(nowSeconds: Long): WatchOtpCode? = otp?.takeIf {
    nowSeconds >= generatedAt.epochSecond &&
        nowSeconds / it.periodSeconds == generatedAt.epochSecond / it.periodSeconds
}
