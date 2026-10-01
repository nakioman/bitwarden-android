package com.x8bit.bitwarden.wear.data.platform.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides shared platform services used by the watch data layer. */
@Module
@InstallIn(SingletonComponent::class)
object WatchPlatformModule {
    /** Provides the existing core dispatcher implementation. */
    @Provides
    @Singleton
    fun provideDispatcherManager(): DispatcherManager = DispatcherManagerImpl()
}
