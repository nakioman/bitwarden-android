package com.x8bit.bitwarden.wear.data.otp.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.wear.data.otp.WatchOtpGenerator
import com.x8bit.bitwarden.wear.data.otp.WatchOtpGeneratorImpl
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCodeSource
import com.x8bit.bitwarden.wear.data.otp.WatchVerificationCodeSourceImpl
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Provides local OTP generation and seed-free presentation data. */
@Module
@InstallIn(SingletonComponent::class)
object WatchOtpModule {
    /** Uses the same SDK artifact and generator as the phone apps. */
    @Provides
    @Singleton
    fun provideWatchOtpGenerator(dispatcherManager: DispatcherManager): WatchOtpGenerator =
        WatchOtpGeneratorImpl(dispatcherManager)

    /** Reads protected accounts on demand without retaining a plaintext seed cache. */
    @Provides
    @Singleton
    fun provideWatchVerificationCodeSource(
        snapshotManager: WatchSnapshotManager,
        securityManager: WatchSecurityManager,
        generator: WatchOtpGenerator,
        clock: Clock,
        dispatcherManager: DispatcherManager,
    ): WatchVerificationCodeSource = WatchVerificationCodeSourceImpl(
        snapshotManager, securityManager, generator, clock, dispatcherManager,
    )
}
