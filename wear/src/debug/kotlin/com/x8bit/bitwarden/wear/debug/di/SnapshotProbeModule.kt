package com.x8bit.bitwarden.wear.debug.di

import android.content.Context
import android.util.AtomicFile
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.sync.WatchEnvelopeDiskSourceImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManagerImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Separates synthetic probe state from every companion enrollment and cache. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SnapshotTransportProbe

/** Only debug components may inject this synthetic-only recipient. */
@Module
@InstallIn(SingletonComponent::class)
object SnapshotProbeModule {
    /** Uses a separate Keystore alias and private file outside backup locations. */
    @Provides
    @Singleton
    @SnapshotTransportProbe
    fun provideProbeManager(
        @ApplicationContext context: Context,
        securityManager: WatchSecurityManager,
        dispatcherManager: DispatcherManager,
    ): WatchSnapshotManager = WatchSnapshotManagerImpl(
        securityManager = securityManager,
        keyManager = WatchSyncKeyManagerImpl("${context.packageName}.wear.transport.probe.v1"),
        diskSource = WatchEnvelopeDiskSourceImpl(
            AtomicFile(File(context.noBackupFilesDir, "wear-transport-probe-v1")),
        ),
        dispatcherManager = dispatcherManager,
    )
}
