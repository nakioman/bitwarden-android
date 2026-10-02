package com.x8bit.bitwarden.wear.debug.data

import com.google.android.gms.wearable.DataItem

/** Processes DataItems from the authenticated Google Play services callback. */
interface WatchDataListenerManager {
    /** Rejects unrelated items before downloading and confirms only persisted commands. */
    suspend fun receive(item: DataItem): Result<Unit>
}
