package com.x8bit.bitwarden.data.wear.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearOtpAccountSource
import com.x8bit.bitwarden.data.wear.WearOtpAccountSourceImpl
import com.x8bit.bitwarden.data.wear.WearOtpIconSource
import com.x8bit.bitwarden.data.wear.WearOtpIconSourceImpl
import com.x8bit.bitwarden.data.wear.WearOtpSource
import com.x8bit.bitwarden.data.wear.WearOtpSourceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides authorized OTP preparation without starting synchronization. */
@Module
@InstallIn(SingletonComponent::class)
object WearOtpModule {
    /** Provides current account and unlock eligibility. */
    @Provides
    @Singleton
    fun provideWearOtpAccountSource(
        authRepository: AuthRepository,
        lockManager: VaultLockManager,
    ): WearOtpAccountSource = WearOtpAccountSourceImpl(authRepository, lockManager)

    /** Provides the existing phone icon policy. */
    @Provides
    @Singleton
    fun provideWearOtpIconSource(
        environmentRepository: EnvironmentRepository,
        settingsRepository: SettingsRepository,
    ): WearOtpIconSource = WearOtpIconSourceImpl(environmentRepository, settingsRepository)

    /** Provides transient OTP reads from the active vault. */
    @Provides
    @Singleton
    fun provideWearOtpSource(
        diskSource: VaultDiskSource,
        sdkSource: VaultSdkSource,
        accountSource: WearOtpAccountSource,
        iconSource: WearOtpIconSource,
        consentSource: WearConsentDiskSource,
        dispatcherManager: DispatcherManager,
    ): WearOtpSource = WearOtpSourceImpl(
        diskSource, sdkSource, accountSource, iconSource, consentSource, dispatcherManager,
    )
}
