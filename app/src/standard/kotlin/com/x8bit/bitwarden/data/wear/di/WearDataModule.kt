package com.x8bit.bitwarden.data.wear.di

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.data.wear.WearDataSource
import com.x8bit.bitwarden.data.wear.WearDataSourceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the persistent Google Play transport without starting synchronization. */
@Module
@InstallIn(SingletonComponent::class)
object WearDataModule {
    /** Reads watch keys and publishes encrypted snapshots. */
    @Provides
    @Singleton
    fun provideWearDataSource(@ApplicationContext context: Context): WearDataSource =
        WearDataSourceImpl(Wearable.getDataClient(context), Wearable.getNodeClient(context))
}
