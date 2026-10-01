package com.x8bit.bitwarden.wear.ui.security

import android.os.Parcelable
import com.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.update
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

/** Rechecks local access on each foreground session; unlock decisions are never restored. */
@HiltViewModel
class WatchSecurityViewModel @Inject constructor(
    private val watchSecurityManager: WatchSecurityManager,
) : BaseViewModel<WatchSecurityState, WatchSecurityEvent, WatchSecurityAction>(
    initialState = WatchSecurityState(),
) {
    override fun handleAction(action: WatchSecurityAction) {
        when (action) {
            WatchSecurityAction.Resume -> mutableStateFlow.update {
                it.copy(status = watchSecurityManager.getStatus())
            }

            WatchSecurityAction.Pause -> mutableStateFlow.update {
                it.copy(status = WatchSecurityStatus.UNVERIFIED)
            }

            WatchSecurityAction.OpenSettings -> sendEvent(WatchSecurityEvent.OpenSettings)
        }
    }
}

/** Current access state, initially denying access until the system has been checked. */
@Parcelize
data class WatchSecurityState(
    val status: WatchSecurityStatus = WatchSecurityStatus.UNVERIFIED,
) : Parcelable

/** Actions originating from the watch screen and its lifecycle. */
sealed class WatchSecurityAction {
    /** Recheck security after returning to the foreground. */
    data object Resume : WatchSecurityAction()

    /** Discard the previous access decision when leaving the foreground. */
    data object Pause : WatchSecurityAction()

    /** Open the watch settings to configure a screen lock. */
    data object OpenSettings : WatchSecurityAction()
}

/** One-shot navigation requests for watch security. */
sealed class WatchSecurityEvent {
    /** Open system settings without collecting the device credential in the app. */
    data object OpenSettings : WatchSecurityEvent()
}
