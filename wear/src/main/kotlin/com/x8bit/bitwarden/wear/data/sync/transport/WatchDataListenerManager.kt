package com.x8bit.bitwarden.wear.data.sync.transport

import com.google.android.gms.wearable.DataItem

/** Processes DataItems from the authenticated Google Play services callback. */
interface WatchDataListenerManager {
    /** Rejects unrelated items before downloading and confirms only persisted commands. */
    suspend fun receive(item: DataItem): Result<Unit>
}
