package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.wear.WearConsentManager
import com.x8bit.bitwarden.data.wear.WearConsentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

/** Presents consent for the account that opened the screen, without restoring authorization. */
@HiltViewModel
class WearSyncViewModel @Inject constructor(
    private val consentManager: WearConsentManager,
    private val authRepository: AuthRepository,
) : BaseViewModel<WearSyncState, WearSyncEvent, WearSyncAction>(WearSyncState()) {
    private val userId = authRepository.userStateFlow.value?.activeUserId
    private var operation: Job? = null

    init {
        authRepository.userStateFlow
            .map { WearSyncAction.Internal.AccountChanged(it?.activeUserId) }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: WearSyncAction) {
        when (action) {
            WearSyncAction.Back -> sendEvent(WearSyncEvent.NavigateBack)
            WearSyncAction.Refresh -> handleRefresh()
            is WearSyncAction.DeviceClick -> handleDeviceClick(action.id)
            WearSyncAction.Dismiss -> mutableStateFlow.update { it.copy(pendingDevice = null) }
            WearSyncAction.Confirm -> handleConfirm()
            is WearSyncAction.Internal.Loaded -> handleLoaded(action.result)
            is WearSyncAction.Internal.AccountChanged -> {
                if (userId == null || action.userId != userId) {
                    operation?.cancel()
                    mutableStateFlow.value = WearSyncState(error = WearSyncError.VAULT_LOCKED)
                }
            }
        }
    }

    private fun handleRefresh() {
        val accountId = userId ?: return handleLoaded(WearConsentResult.VaultLocked)
        operation?.cancel()
        mutableStateFlow.update { it.copy(isLoading = true, pendingDevice = null, error = null) }
        operation = viewModelScope.launch {
            sendAction(WearSyncAction.Internal.Loaded(consentManager.getDevices(accountId)))
        }
    }

    private fun handleDeviceClick(id: String) {
        if (state.isLoading || state.error != null) return
        val device = state.devices.find { it.id == id } ?: return
        if (!device.isReachable && !device.isAuthorized) return
        mutableStateFlow.update { it.copy(pendingDevice = device) }
    }

    private fun handleConfirm() {
        val accountId = userId ?: return handleLoaded(WearConsentResult.VaultLocked)
        val device = state.pendingDevice ?: return
        mutableStateFlow.update { it.copy(isLoading = true, pendingDevice = null) }
        operation?.cancel()
        operation = viewModelScope.launch {
            sendAction(
                WearSyncAction.Internal.Loaded(
                    consentManager.setConsent(accountId, device.id, !device.isAuthorized),
                ),
            )
        }
    }

    private fun handleLoaded(result: WearConsentResult) {
        if (userId == null || authRepository.userStateFlow.value?.activeUserId != userId) {
            mutableStateFlow.value = WearSyncState(error = WearSyncError.VAULT_LOCKED)
            return
        }
        mutableStateFlow.value = when (result) {
            is WearConsentResult.Success -> WearSyncState(
                devices = result.devices.map {
                    WearSyncDeviceState(it.id, it.name, it.isAuthorized, it.isReachable)
                },
            )

            WearConsentResult.VaultLocked -> WearSyncState(error = WearSyncError.VAULT_LOCKED)
            WearConsentResult.Unavailable -> WearSyncState(error = WearSyncError.UNAVAILABLE)
            WearConsentResult.Error -> WearSyncState(error = WearSyncError.FAILURE)
        }
    }
}

/** UI state; account identifiers and device permissions are reloaded from the guarded manager. */
@Parcelize
data class WearSyncState(
    val devices: List<WearSyncDeviceState> = emptyList(),
    val isLoading: Boolean = false,
    val pendingDevice: WearSyncDeviceState? = null,
    val error: WearSyncError? = null,
) : Parcelable

/** Display information for a watch. */
@Parcelize
data class WearSyncDeviceState(
    val id: String,
    val name: String,
    val isAuthorized: Boolean,
    val isReachable: Boolean,
) : Parcelable

/** Non-sensitive errors shown on the consent screen. */
enum class WearSyncError { VAULT_LOCKED, UNAVAILABLE, FAILURE }

/** Actions from the consent screen. */
sealed class WearSyncAction {
    /** Return to settings. */
    data object Back : WearSyncAction()
    /** Reload current device reachability and consent. */
    data object Refresh : WearSyncAction()
    /** Request a confirmation for the selected device. */
    data class DeviceClick(val id: String) : WearSyncAction()
    /** Dismiss a pending consent decision. */
    data object Dismiss : WearSyncAction()
    /** Confirm the displayed consent decision. */
    data object Confirm : WearSyncAction()
    /** Internal events from account and device sources. */
    sealed class Internal : WearSyncAction() {
        /** A guarded device operation completed. */
        data class Loaded(val result: WearConsentResult) : Internal()
        /** The active account may have changed. */
        data class AccountChanged(val userId: String?) : Internal()
    }
}

/** Navigation requests for this screen. */
sealed class WearSyncEvent {
    /** Return to the previous screen. */
    data object NavigateBack : WearSyncEvent()
}
