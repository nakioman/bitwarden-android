package com.x8bit.bitwarden.wear.data.sync.di

import android.content.Context
import android.util.AtomicFile
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.sync.WatchEnvelopeDiskSource
import com.x8bit.bitwarden.wear.data.sync.WatchEnvelopeDiskSourceImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSecureStorageManager
import com.x8bit.bitwarden.wear.data.sync.WatchSecureStorageManagerImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManager
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Provides the watch-only recipient key and encrypted snapshot cache. */
@Module
@InstallIn(SingletonComponent::class)
object WatchSyncModule {
    /** Provides the watch's non-exportable recipient key. */
    @Provides
    @Singleton
    fun provideWatchSyncKeyManager(): WatchSyncKeyManager = WatchSyncKeyManagerImpl()

    /** Keeps ciphertext outside Android cloud and device transfer backups. */
    @Provides
    @Singleton
    fun provideWatchEnvelopeDiskSource(
        @ApplicationContext context: Context,
    ): WatchEnvelopeDiskSource =
        WatchEnvelopeDiskSourceImpl(AtomicFile(File(context.noBackupFilesDir, "wear-snapshot-v1")))

    /** Guards access with the watch's existing system screen lock. */
    @Provides
    @Singleton
    fun provideWatchSecureStorageManager(
        securityManager: WatchSecurityManager,
        keyManager: WatchSyncKeyManager,
        diskSource: WatchEnvelopeDiskSource,
        dispatcherManager: DispatcherManager,
    ): WatchSecureStorageManager = WatchSecureStorageManagerImpl(
        securityManager, keyManager, diskSource, dispatcherManager,
    )
}
