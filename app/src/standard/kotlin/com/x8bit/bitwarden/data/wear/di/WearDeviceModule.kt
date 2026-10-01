package com.x8bit.bitwarden.data.wear.di

import android.content.Context
import com.x8bit.bitwarden.data.wear.WearDeviceSource
import com.x8bit.bitwarden.data.wear.WearDeviceSourceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides companion discovery in Google Play builds. */
@Module
@InstallIn(SingletonComponent::class)
object WearDeviceModule {
    /** Provides app-scoped Wear discovery. */
    @Provides
    @Singleton
    fun provideWearDeviceSource(@ApplicationContext context: Context): WearDeviceSource =
        WearDeviceSourceImpl(context)
}
