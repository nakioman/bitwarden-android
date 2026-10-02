package com.x8bit.bitwarden.data.wear.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.wear.WearConsentDiskSource
import com.x8bit.bitwarden.data.wear.WearDataSource
import com.x8bit.bitwarden.data.wear.WearDeliveryManager
import com.x8bit.bitwarden.data.wear.WearDeliveryManagerImpl
import com.x8bit.bitwarden.data.wear.WearOutboxManager
import com.x8bit.bitwarden.data.wear.WearDeliveryEventManager
import com.x8bit.bitwarden.data.wear.WearDeliveryEventManagerImpl
import com.x8bit.bitwarden.data.wear.WearSyncCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides authorized delivery and authenticated transport event processing. */
@Module
@InstallIn(SingletonComponent::class)
object WearDeliveryModule {
    /** Retries ciphertext and checks exact watch-owned receipts. */
    @Provides
    @Singleton
    fun provideWearDeliveryManager(
        outbox: WearOutboxManager,
        consentSource: WearConsentDiskSource,
        authRepository: AuthRepository,
        dataSource: WearDataSource,
        dispatcherManager: DispatcherManager,
    ): WearDeliveryManager = WearDeliveryManagerImpl(
        outbox, consentSource, authRepository, dataSource, dispatcherManager,
    )
    /** Processes hints without granting permission or accepting unverified acknowledgment data. */
    @Provides
    @Singleton
    fun provideWearDeliveryEventManager(
        outbox: WearOutboxManager,
        coordinator: WearSyncCoordinator,
    ): WearDeliveryEventManager = WearDeliveryEventManagerImpl(outbox, coordinator)
}
