package com.x8bit.bitwarden.data.wear.di

import com.x8bit.bitwarden.data.wear.WearDeviceSource
import com.x8bit.bitwarden.data.wear.WearDeviceSourceImpl
import com.x8bit.bitwarden.data.wear.WearKeySource
import com.x8bit.bitwarden.data.wear.WearKeySourceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the unavailable companion implementation for F-Droid. */
@Module
@InstallIn(SingletonComponent::class)
object WearDeviceModule {
    /** Provides discovery without adding Google dependencies. */
    @Provides
    @Singleton
    fun provideWearDeviceSource(): WearDeviceSource = WearDeviceSourceImpl()

    /** Provides public-key exchange for this distribution. */
    @Provides
    @Singleton
    fun provideWearKeySource(): WearKeySource = WearKeySourceImpl()
}
