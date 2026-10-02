package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.BuildConfig

/** Enables the companion only in the flavor that provides Google Play services. */
object WearSyncAvailability {
    /** Supported consistently in debug, beta and release; F-Droid has no Data Layer transport. */
    val isEnabled: Boolean = BuildConfig.FLAVOR == "standard"
}
