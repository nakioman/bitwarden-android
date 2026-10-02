package com.x8bit.bitwarden.data.wear.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearDeliveryManager
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearSnapshotPreparer
import com.x8bit.bitwarden.data.wear.WearSyncCoordinator
import com.x8bit.bitwarden.data.wear.WearSyncCoordinatorImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides lifecycle coordination without starting account observers or transfers. */
@Module
@InstallIn(SingletonComponent::class)
object WearSyncModule {
    /** Creates the serialized coordinator for trusted lifecycle events. */
    @Provides
    @Singleton
    fun provideWearSyncCoordinator(
        consentSource: WearConsentDiskSource,
        preparer: WearSnapshotPreparer,
        outbox: WearOutboxManager,
        deliveryManager: WearDeliveryManager,
        dispatcherManager: DispatcherManager,
    ): WearSyncCoordinator = WearSyncCoordinatorImpl(
        consentSource, preparer, outbox, deliveryManager, dispatcherManager,
    )
}
