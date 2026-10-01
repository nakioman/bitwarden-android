package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.BuildConfig

/** Temporary availability policy while companion encryption and enrollment are being completed. */
object WearSyncAvailability {
    /** Enables the in-progress flow only in the Google Play development variant. */
    val isEnabled: Boolean = BuildConfig.DEBUG && BuildConfig.FLAVOR == "standard"
}
