package com.x8bit.bitwarden.data.wear.di

import com.x8bit.bitwarden.data.wear.WearDataSource
import com.x8bit.bitwarden.data.wear.WearDataSourceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides unavailable transport in builds without Google Play services. */
@Module
@InstallIn(SingletonComponent::class)
object WearDataModule {
    /** Returns an explicit failure rather than attempting a Wear operation. */
    @Provides
    @Singleton
    fun provideWearDataSource(): WearDataSource = WearDataSourceImpl()
}
