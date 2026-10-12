package com.x8bit.bitwarden.data.wear.di

import android.content.SharedPreferences
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.data.datasource.disk.di.UnencryptedPreferences
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.wear.WearDataSource
import com.x8bit.bitwarden.data.wear.WearOtpAccountSource
import com.x8bit.bitwarden.data.wear.WearOtpSource
import com.x8bit.bitwarden.data.wear.WearSnapshotStore
import com.x8bit.bitwarden.data.wear.WearSnapshotStoreImpl
import com.x8bit.bitwarden.data.wear.WearSyncDiskSource
import com.x8bit.bitwarden.data.wear.WearSyncDiskSourceImpl
import com.x8bit.bitwarden.data.wear.WearSyncEventSource
import com.x8bit.bitwarden.data.wear.WearSyncEventSourceImpl
import com.x8bit.bitwarden.data.wear.WearSyncLifecycleManager
import com.x8bit.bitwarden.data.wear.WearSyncLifecycleManagerImpl
import com.x8bit.bitwarden.data.wear.WearSyncLogoutHandler
import com.x8bit.bitwarden.data.wear.WearSyncLogoutHandlerImpl
import com.x8bit.bitwarden.data.wear.WearSyncManager
import com.x8bit.bitwarden.data.wear.WearSyncManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Provides watch sync and its process-lifetime observation. */
@Module
@InstallIn(SingletonComponent::class)
object WearSyncModule {
    /** Provides the per-account sync setting. */
    @Provides
    @Singleton
    fun provideWearSyncDiskSource(
        @UnencryptedPreferences sharedPreferences: SharedPreferences,
    ): WearSyncDiskSource = WearSyncDiskSourceImpl(sharedPreferences)

    /** Provides serialized snapshot publication and removal. */
    @Provides
    @Singleton
    fun provideWearSnapshotStore(
        dataSource: WearDataSource,
        dispatcherManager: DispatcherManager,
    ): WearSnapshotStore = WearSnapshotStoreImpl(dataSource, dispatcherManager)

    /** Provides watch sync for the active account. */
    @Provides
    @Singleton
    fun provideWearSyncManager(
        authRepository: AuthRepository,
        accountSource: WearOtpAccountSource,
        diskSource: WearSyncDiskSource,
        otpSource: WearOtpSource,
        snapshotStore: WearSnapshotStore,
    ): WearSyncManager = WearSyncManagerImpl(
        authRepository, accountSource, diskSource, otpSource, snapshotStore,
    )

    /** Logout handling depends only on storage, avoiding an auth repository dependency cycle. */
    @Provides
    @Singleton
    fun provideWearSyncLogoutHandler(
        diskSource: WearSyncDiskSource,
        snapshotStore: WearSnapshotStore,
        dispatcherManager: DispatcherManager,
    ): WearSyncLogoutHandler = WearSyncLogoutHandlerImpl(
        diskSource, snapshotStore, CoroutineScope(SupervisorJob() + dispatcherManager.io),
    )

    /** Observes local vault and setting changes. */
    @Provides
    @Singleton
    fun provideWearSyncEventSource(
        authRepository: AuthRepository,
        vaultDiskSource: VaultDiskSource,
        lockManager: VaultLockManager,
        diskSource: WearSyncDiskSource,
    ): WearSyncEventSource = WearSyncEventSourceImpl(
        authRepository, vaultDiskSource, lockManager, diskSource,
    )

    /** Keeps watches updated after leaving the settings screen. */
    @Provides
    @Singleton
    fun provideWearSyncLifecycleManager(
        eventSource: WearSyncEventSource,
        syncManager: WearSyncManager,
        dispatcherManager: DispatcherManager,
    ): WearSyncLifecycleManager = WearSyncLifecycleManagerImpl(
        eventSource, syncManager, CoroutineScope(SupervisorJob() + dispatcherManager.io),
    )
}
