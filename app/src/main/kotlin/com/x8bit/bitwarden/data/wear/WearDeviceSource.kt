package com.x8bit.bitwarden.data.wear

/** Discovers reachable watches running the companion app. */
interface WearDeviceSource {
    /** Whether companion discovery is available in this build. */
    val isSupported: Boolean

    /** Returns reachable companion devices without opening the vault. */
    suspend fun getDevices(): Result<List<WearDevice>>
}

/** A companion device; its identity comes from the Wear data layer. */
data class WearDevice(val id: String, val name: String)
