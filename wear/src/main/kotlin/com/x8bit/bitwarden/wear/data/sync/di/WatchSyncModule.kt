package com.x8bit.bitwarden.wear.data.sync.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManagerImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManager
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManagerImpl
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the watch recipient key and snapshot access. */
@Module
@InstallIn(SingletonComponent::class)
object WatchSyncModule {
    /** Provides the watch's non-exportable recipient key. */
    @Provides
    @Singleton
    fun provideWatchSyncKeyManager(): WatchSyncKeyManager = WatchSyncKeyManagerImpl()

    /** Provides key publication and snapshot decryption. */
    @Provides
    @Singleton
    fun provideWatchSnapshotManager(
        securityManager: WatchSecurityManager,
        keyManager: WatchSyncKeyManager,
        transport: WatchDataTransport,
        dispatcherManager: DispatcherManager,
    ): WatchSnapshotManager = WatchSnapshotManagerImpl(
        securityManager, keyManager, transport, dispatcherManager,
    )
}
