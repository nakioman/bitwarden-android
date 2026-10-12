package com.x8bit.bitwarden.wear.data.sync.di

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataTransport
import com.x8bit.bitwarden.wear.data.sync.transport.WatchDataTransportImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Google Play Data Layer transport. */
@Module
@InstallIn(SingletonComponent::class)
object WatchDataModule {
    /** Provides key publication and snapshot reads. */
    @Provides
    @Singleton
    fun provideWatchDataTransport(@ApplicationContext context: Context): WatchDataTransport =
        WatchDataTransportImpl(Wearable.getDataClient(context), Wearable.getNodeClient(context))
}
