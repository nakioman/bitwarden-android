package com.x8bit.bitwarden.data.wear

/** Withdraws account permissions independently of vault access and connectivity. */
interface WearConsentRevoker {
    /** Durably withdraws consent for an explicit logout without deleting its recovery journal. */
    fun revokeAccount(userId: String): Result<Unit>
}
