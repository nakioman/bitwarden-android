package com.x8bit.bitwarden.wear.debug.di

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.wear.data.sync.WatchDataReceiver
import com.x8bit.bitwarden.wear.debug.data.WatchDataListenerManager
import com.x8bit.bitwarden.wear.debug.data.WatchDataListenerManagerImpl
import com.x8bit.bitwarden.wear.debug.data.WatchDataTransport
import com.x8bit.bitwarden.wear.debug.data.WatchDataTransportImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds Google Play transport only in the currently enabled debug companion build. */
@Module
@InstallIn(SingletonComponent::class)
object WatchDataModule {
    /** Provides shared bounded asset access and watch-owned receipts. */
    @Provides
    @Singleton
    fun provideWatchDataTransport(@ApplicationContext context: Context): WatchDataTransport =
        WatchDataTransportImpl(Wearable.getDataClient(context), Wearable.getNodeClient(context))

    /** Uses production persistence, independently of synthetic probe state. */
    @Provides
    @Singleton
    fun provideWatchDataListenerManager(
        transport: WatchDataTransport,
        receiver: WatchDataReceiver,
    ): WatchDataListenerManager = WatchDataListenerManagerImpl(transport, receiver)
}
