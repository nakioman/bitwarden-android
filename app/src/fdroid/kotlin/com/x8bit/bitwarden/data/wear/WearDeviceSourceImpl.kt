package com.x8bit.bitwarden.data.wear

/** Keeps F-Droid builds independent of Google Play services. */
class WearDeviceSourceImpl : WearDeviceSource {
    override val isSupported: Boolean = false

    override suspend fun getDevices(): Result<List<WearDevice>> = Result.success(emptyList())
}
