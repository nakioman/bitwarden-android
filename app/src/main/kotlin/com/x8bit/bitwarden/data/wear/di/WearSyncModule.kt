package com.x8bit.bitwarden.data.wear.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearDeliveryManager
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearSnapshotPreparer
import com.x8bit.bitwarden.data.wear.WearSyncCoordinator
import com.x8bit.bitwarden.data.wear.WearSyncCoordinatorImpl
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.wear.WearConsentRevoker
import com.x8bit.bitwarden.data.wear.WearConsentRevokerImpl
import com.x8bit.bitwarden.data.wear.WearSyncEventSource
import com.x8bit.bitwarden.data.wear.WearSyncEventSourceImpl
import com.x8bit.bitwarden.data.wear.WearSyncLifecycleManager
import com.x8bit.bitwarden.data.wear.WearSyncLifecycleManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides durable withdrawal and process-lifetime lifecycle coordination. */
@Module
@InstallIn(SingletonComponent::class)
object WearSyncModule {
    /** Creates the serialized coordinator for trusted lifecycle events. */
    @Provides
    @Singleton
    fun provideWearSyncCoordinator(
        consentSource: WearConsentDiskSource,
        preparer: WearSnapshotPreparer,
        outbox: WearOutboxManager,
        deliveryManager: WearDeliveryManager,
        dispatcherManager: DispatcherManager,
    ): WearSyncCoordinator = WearSyncCoordinatorImpl(
        consentSource, preparer, outbox, deliveryManager, dispatcherManager,
    )
    /** Withdrawal depends only on disk, avoiding an authentication repository dependency cycle. */
    @Provides
    @Singleton
    fun provideWearConsentRevoker(consentSource: WearConsentDiskSource): WearConsentRevoker =
        WearConsentRevokerImpl(consentSource)

    /** Observes the existing phone vault update path without introducing server polling. */
    @Provides
    @Singleton
    fun provideWearSyncEventSource(
        authRepository: AuthRepository,
        vaultDiskSource: VaultDiskSource,
        lockManager: VaultLockManager,
        consentSource: WearConsentDiskSource,
    ): WearSyncEventSource = WearSyncEventSourceImpl(
        authRepository, vaultDiskSource, lockManager, consentSource,
    )

    /** Keeps command processing alive after leaving the consent screen. */
    @Provides
    @Singleton
    fun provideWearSyncLifecycleManager(
        eventSource: WearSyncEventSource,
        coordinator: WearSyncCoordinator,
        dispatcherManager: DispatcherManager,
    ): WearSyncLifecycleManager = WearSyncLifecycleManagerImpl(
        eventSource, coordinator, CoroutineScope(SupervisorJob() + dispatcherManager.io),
    )
}
