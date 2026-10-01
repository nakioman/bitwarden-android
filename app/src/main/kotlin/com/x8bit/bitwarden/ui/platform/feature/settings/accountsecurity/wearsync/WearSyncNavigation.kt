package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.wearsync

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/** Type-safe route for Wear companion consent. */
@Serializable
data object WearSyncRoute

/** Adds the companion settings destination. */
fun NavGraphBuilder.wearSyncDestination(onNavigateBack: () -> Unit) {
    composableWithPushTransitions<WearSyncRoute> {
        WearSyncScreen(onNavigateBack = onNavigateBack)
    }
}

/** Opens Wear companion settings. */
fun NavController.navigateToWearSync() {
    navigate(WearSyncRoute)
}
