package com.x8bit.bitwarden.wear.data.sync.di

import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManager
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the watch-only recipient key shared by protected snapshot operations. */
@Module
@InstallIn(SingletonComponent::class)
object WatchSyncModule {
    /** Provides the watch's non-exportable recipient key. */
    @Provides
    @Singleton
    fun provideWatchSyncKeyManager(): WatchSyncKeyManager = WatchSyncKeyManagerImpl()
}
