package com.x8bit.bitwarden.data.wear.di

import android.content.SharedPreferences
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.data.datasource.disk.di.KeystoreEncryptedPreferences
import com.x8bit.bitwarden.data.wear.WearOutboxDiskSource
import com.x8bit.bitwarden.data.wear.WearOutboxDiskSourceImpl
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearOutboxManagerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Provides durable ciphertext delivery state, without starting transfers. */
@Module
@InstallIn(SingletonComponent::class)
object WearOutboxModule {
    /** Provides the encrypted, atomically committed ledger. */
    @Provides
    @Singleton
    fun provideWearOutboxDiskSource(
        @KeystoreEncryptedPreferences preferences: SharedPreferences,
        json: Json,
    ): WearOutboxDiskSource = WearOutboxDiskSourceImpl(preferences, json)

    /** Provides revision reservation and ciphertext retry state. */
    @Provides
    @Singleton
    fun provideWearOutboxManager(
        diskSource: WearOutboxDiskSource,
        dispatcherManager: DispatcherManager,
    ): WearOutboxManager = WearOutboxManagerImpl(diskSource, dispatcherManager)
}
