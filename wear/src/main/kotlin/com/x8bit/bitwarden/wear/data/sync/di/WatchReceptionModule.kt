package com.x8bit.bitwarden.wear.data.sync.di

import android.content.Context
import android.util.AtomicFile
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.sync.WatchDataReceiver
import com.x8bit.bitwarden.wear.data.sync.WatchDataReceiverImpl
import com.x8bit.bitwarden.wear.data.sync.WatchEnvelopeDiskSourceImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManagerImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Provides framed production storage without mixing raw legacy cache or synthetic test data. */
@Module
@InstallIn(SingletonComponent::class)
object WatchReceptionModule {
    /** Uses the existing pinned recipient key and a dedicated atomic, non-backed-up framed file. */
    @Provides
    @Singleton
    fun provideWatchSnapshotManager(
        @ApplicationContext context: Context,
        securityManager: WatchSecurityManager,
        keyManager: WatchSyncKeyManager,
        dispatcherManager: DispatcherManager,
    ): WatchSnapshotManager = WatchSnapshotManagerImpl(
        securityManager, keyManager,
        WatchEnvelopeDiskSourceImpl(
            AtomicFile(File(context.noBackupFilesDir, "wear-framed-snapshot-v1")),
        ),
        dispatcherManager,
    )

    /** Keeps persistence-before-receipt logic independent of Google Play callbacks. */
    @Provides
    @Singleton
    fun provideWatchDataReceiver(snapshotManager: WatchSnapshotManager): WatchDataReceiver =
        WatchDataReceiverImpl(snapshotManager)
}
