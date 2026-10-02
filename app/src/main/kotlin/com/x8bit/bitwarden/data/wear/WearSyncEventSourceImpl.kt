package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.manager.model.VaultStateEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Observes the existing local vault stream; server updates follow the normal phone sync path.
 *
 * @property authRepository Current account identity; null never implies logout here.
 * @property vaultDiskSource Account-scoped encrypted cipher changes, including local edits.
 * @property lockManager Existing vault unlock events.
 * @property consentSource Committed authorization and withdrawal notifications.
 * @property isEnabled Availability of the companion flow in this build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncEventSourceImpl(
    private val authRepository: AuthRepository,
    private val vaultDiskSource: VaultDiskSource,
    private val lockManager: VaultLockManager,
    private val consentSource: WearConsentDiskSource,
    private val isEnabled: Boolean = WearSyncAvailability.isEnabled,
) : WearSyncEventSource {
    override val events: Flow<WearSyncEvent>
        get() = if (isEnabled) {
            merge(accountEvents(), unlockEvents(), consentSource.changes.map { activeEvent() })
        } else {
            emptyFlow()
        }

    private fun accountEvents(): Flow<WearSyncEvent> = authRepository.userStateFlow
        .flatMapLatest { user ->
            val userId = user?.activeUserId
            if (userId == null) {
                flowOf(WearSyncEvent.Retry)
            } else {
                vaultDiskSource.getCiphersFlow(userId).map { WearSyncEvent.Refresh(userId) }
            }
        }

    private fun unlockEvents(): Flow<WearSyncEvent> = lockManager.vaultStateEventFlow.map {
        when (it) {
            is VaultStateEvent.Unlocked -> WearSyncEvent.Refresh(it.userId)
            is VaultStateEvent.Locked -> WearSyncEvent.Retry
        }
    }

    private fun activeEvent(): WearSyncEvent = authRepository.userStateFlow.value?.activeUserId
        ?.let { WearSyncEvent.Refresh(it) } ?: WearSyncEvent.Retry
}
