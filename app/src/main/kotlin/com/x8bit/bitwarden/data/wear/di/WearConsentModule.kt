package com.x8bit.bitwarden.data.wear.di

import android.content.SharedPreferences
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.data.datasource.disk.di.KeystoreEncryptedPreferences
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearConsentDiskSourceImpl
import com.x8bit.bitwarden.data.wear.WearConsentManager
import com.x8bit.bitwarden.data.wear.WearConsentManagerImpl
import com.x8bit.bitwarden.data.wear.WearDeviceSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Provides the local phone consent ledger and account guards. */
@Module
@InstallIn(SingletonComponent::class)
object WearConsentModule {
    /** Provides encrypted consent persistence. */
    @Provides
    @Singleton
    fun provideWearConsentDiskSource(
        @KeystoreEncryptedPreferences preferences: SharedPreferences,
        json: Json,
    ): WearConsentDiskSource = WearConsentDiskSourceImpl(preferences, json)

    /** Provides consent management without accessing OTP data. */
    @Provides
    @Singleton
    fun provideWearConsentManager(
        deviceSource: WearDeviceSource,
        diskSource: WearConsentDiskSource,
        authRepository: AuthRepository,
        vaultLockManager: VaultLockManager,
        dispatcherManager: DispatcherManager,
    ): WearConsentManager = WearConsentManagerImpl(
        deviceSource, diskSource, authRepository, vaultLockManager, dispatcherManager,
    )
}
