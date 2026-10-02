package com.x8bit.bitwarden.data.wear.di

import android.content.Context
import android.content.SharedPreferences
import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.data.datasource.disk.di.KeystoreEncryptedPreferences
import com.google.android.gms.wearable.Wearable
import com.x8bit.bitwarden.data.wear.WearDataSource
import com.x8bit.bitwarden.data.wear.WearDataSourceImpl
import com.x8bit.bitwarden.data.wear.WearOutboxDiskSource
import com.x8bit.bitwarden.data.wear.WearOutboxDiskSourceImpl
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearOutboxManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** Keeps test delivery state and routes separate from authorized companion synchronization. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PersistentTransportProbe

/** Debug-only synthetic outbox and persistent transport. */
@Module
@InstallIn(SingletonComponent::class)
object PersistentProbeModule {
    /** Synthetic ciphertext only; never writes the production ledger. */
    @Provides
    @Singleton
    @PersistentTransportProbe
    fun provideDiskSource(
        @KeystoreEncryptedPreferences preferences: SharedPreferences,
        json: Json,
    ): WearOutboxDiskSource = WearOutboxDiskSourceImpl(
        preferences, json, "wearSyntheticPersistentOutboxV1",
    )

    /** Uses the same revision and acknowledgment rules as the production outbox. */
    @Provides
    @Singleton
    @PersistentTransportProbe
    fun provideOutbox(
        @PersistentTransportProbe diskSource: WearOutboxDiskSource,
        dispatcherManager: DispatcherManager,
    ): WearOutboxManager = WearOutboxManagerImpl(diskSource, dispatcherManager)

    /** Routes every asset and receipt exclusively to the debug receiver. */
    @Provides
    @Singleton
    @PersistentTransportProbe
    fun provideDataSource(
        @ApplicationContext context: Context,
        uuidManager: UuidManager,
    ): WearDataSource = WearDataSourceImpl(
        Wearable.getDataClient(context), uuidManager, WearDataChannel.SYNTHETIC,
    )
}
