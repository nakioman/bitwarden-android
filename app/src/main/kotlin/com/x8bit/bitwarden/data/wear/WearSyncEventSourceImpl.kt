package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.manager.model.VaultStateEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Observes the local vault rather than the server; server updates follow the normal phone sync.
 *
 * @property authRepository Active account identity.
 * @property vaultDiskSource Account-scoped cipher changes, including local edits.
 * @property lockManager Vault unlock events.
 * @property diskSource Sync setting changes.
 * @property isEnabled Availability of watch sync in this build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSyncEventSourceImpl(
    private val authRepository: AuthRepository,
    private val vaultDiskSource: VaultDiskSource,
    private val lockManager: VaultLockManager,
    private val diskSource: WearSyncDiskSource,
    private val isEnabled: Boolean = WearSyncAvailability.isEnabled,
) : WearSyncEventSource {
    override val events: Flow<Unit>
        get() = if (isEnabled) {
            merge(cipherEvents(), unlockEvents(), diskSource.changes)
        } else {
            emptyFlow()
        }

    // Premium changes which codes are eligible, so they also restart observation.
    private fun cipherEvents(): Flow<Unit> = authRepository.userStateFlow
        .map { user -> user?.activeUserId?.let { it to user.activeAccount.isPremium } }
        .distinctUntilChanged()
        .flatMapLatest { active ->
            active?.let { vaultDiskSource.getCiphersFlow(it.first).map { } } ?: emptyFlow()
        }

    private fun unlockEvents(): Flow<Unit> = lockManager.vaultStateEventFlow
        .filterIsInstance<VaultStateEvent.Unlocked>()
        .map { }
}
