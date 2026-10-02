package com.x8bit.bitwarden.data.wear.di

import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearOtpAccountSource
import com.x8bit.bitwarden.data.wear.WearOtpSource
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearSnapshotPreparer
import com.x8bit.bitwarden.data.wear.WearSnapshotPreparerImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides command preparation without starting real OTP transfers. */
@Module
@InstallIn(SingletonComponent::class)
object WearSnapshotModule {
    /** Creates encrypted snapshots and empty revocations for the durable queue. */
    @Provides
    @Singleton
    fun provideWearSnapshotPreparer(
        accountSource: WearOtpAccountSource,
        consentSource: WearConsentDiskSource,
        otpSource: WearOtpSource,
        outbox: WearOutboxManager,
        uuidManager: UuidManager,
        dispatcherManager: DispatcherManager,
    ): WearSnapshotPreparer = WearSnapshotPreparerImpl(
        accountSource, consentSource, otpSource, outbox, uuidManager, dispatcherManager,
    )
}
