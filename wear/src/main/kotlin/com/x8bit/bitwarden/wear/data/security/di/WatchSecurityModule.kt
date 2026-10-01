package com.x8bit.bitwarden.wear.data.security.di

import android.app.KeyguardManager
import android.content.Context
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides access to the watch security services. */
@Module
@InstallIn(SingletonComponent::class)
object WatchSecurityModule {
    /** Provides the current system screen lock checks. */
    @Provides
    @Singleton
    fun provideWatchSecurityManager(
        @ApplicationContext context: Context,
    ): WatchSecurityManager = WatchSecurityManagerImpl(
        keyguardManager = context.getSystemService(KeyguardManager::class.java),
    )
}
