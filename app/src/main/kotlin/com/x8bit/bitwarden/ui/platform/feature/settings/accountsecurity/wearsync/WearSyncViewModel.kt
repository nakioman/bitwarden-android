package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.wear.WearDevice
import com.x8bit.bitwarden.data.wear.WearDeviceSource
import com.x8bit.bitwarden.data.wear.WearSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"

/** Manages the active account's watch sync setting and shows watches running Bitwarden. */
@HiltViewModel
class WearSyncViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    authRepository: AuthRepository,
    private val syncManager: WearSyncManager,
    private val deviceSource: WearDeviceSource,
) : BaseViewModel<WearSyncState, WearSyncEvent, WearSyncAction>(
    initialState = savedStateHandle[KEY_STATE] ?: WearSyncState(
        isSyncEnabled = authRepository.userStateFlow.value?.activeUserId
            ?.let(syncManager::isSyncEnabled) == true,
    ),
) {
    private val userId = authRepository.userStateFlow.value?.activeUserId
    private var loadJob: Job? = null

    init {
        stateFlow.onEach { savedStateHandle[KEY_STATE] = it }.launchIn(viewModelScope)
    }

    override fun handleAction(action: WearSyncAction) {
        when (action) {
            WearSyncAction.BackClick -> sendEvent(WearSyncEvent.NavigateBack)
            WearSyncAction.RefreshClick -> handleRefreshClick()
            is WearSyncAction.SyncToggle -> handleSyncToggle(action)
            is WearSyncAction.Internal.WatchesLoaded -> handleWatchesLoaded(action)
            is WearSyncAction.Internal.SyncToggleComplete -> handleSyncToggleComplete(action)
        }
    }

    private fun handleRefreshClick() {
        if (!deviceSource.isSupported) {
            mutableStateFlow.update { it.copy(error = WearSyncError.UNAVAILABLE) }
            return
        }
        loadJob?.cancel()
        mutableStateFlow.update { it.copy(isLoadingWatches = true, error = null) }
        loadJob = viewModelScope.launch {
            sendAction(WearSyncAction.Internal.WatchesLoaded(deviceSource.getDevices()))
        }
    }

    private fun handleSyncToggle(action: WearSyncAction.SyncToggle) {
        val accountId = userId ?: return
        mutableStateFlow.update { it.copy(isSyncEnabled = action.isEnabled, error = null) }
        viewModelScope.launch {
            val result = syncManager.setSyncEnabled(accountId, action.isEnabled)
            sendAction(WearSyncAction.Internal.SyncToggleComplete(result.isSuccess))
        }
    }

    private fun handleWatchesLoaded(action: WearSyncAction.Internal.WatchesLoaded) {
        mutableStateFlow.update { state ->
            action.result.fold(
                onSuccess = { devices ->
                    state.copy(
                        isLoadingWatches = false,
                        watches = devices.map(WearDevice::toWatchState),
                    )
                },
                onFailure = {
                    state.copy(isLoadingWatches = false, error = WearSyncError.LOAD_FAILED)
                },
            )
        }
    }

    private fun handleSyncToggleComplete(action: WearSyncAction.Internal.SyncToggleComplete) {
        if (action.isSuccess) return
        mutableStateFlow.update { it.copy(error = WearSyncError.UPDATE_FAILED) }
    }
}

private fun WearDevice.toWatchState(): WearSyncWatchState = WearSyncWatchState(id, name)

/** UI state for the watch sync screen. */
@Parcelize
data class WearSyncState(
    val isSyncEnabled: Boolean,
    val watches: List<WearSyncWatchState> = emptyList(),
    val isLoadingWatches: Boolean = false,
    val error: WearSyncError? = null,
) : Parcelable

/** A connected watch running Bitwarden. */
@Parcelize
data class WearSyncWatchState(
    val id: String,
    val name: String,
) : Parcelable

/** Non-sensitive errors shown on the watch sync screen. */
enum class WearSyncError {
    /** Watch sync is not supported in this build. */
    UNAVAILABLE,

    /** Connected watches could not be listed. */
    LOAD_FAILED,

    /** Watches could not be updated after changing the setting. */
    UPDATE_FAILED,
}

/** Actions for the watch sync screen. */
sealed class WearSyncAction {
    /** The back button was clicked. */
    data object BackClick : WearSyncAction()

    /** Reload the list of connected watches. */
    data object RefreshClick : WearSyncAction()

    /** The sync switch was toggled. */
    data class SyncToggle(val isEnabled: Boolean) : WearSyncAction()

    /** Actions posted from coroutines. */
    sealed class Internal : WearSyncAction() {
        /** Connected watches finished loading. */
        data class WatchesLoaded(val result: Result<List<WearDevice>>) : Internal()

        /** Applying the sync setting to watches finished. */
        data class SyncToggleComplete(val isSuccess: Boolean) : Internal()
    }
}

/** One-shot events for the watch sync screen. */
sealed class WearSyncEvent {
    /** Return to the previous screen. */
    data object NavigateBack : WearSyncEvent()
}
